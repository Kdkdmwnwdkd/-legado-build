import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'sync_gitee_release.py'
spec = importlib.util.spec_from_file_location('archive_gitee_sync', SCRIPT)
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class GiteeReleaseSafetyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'reader_123.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('AndroidManifest.xml', b'test manifest')
            archive.writestr('classes.dex', b'test payload')
        self.old = {'id': 11, 'name': 'reader_122.apk',
                    'browser_download_url': 'https://gitee.com/test/reader_122.apk'}
        self.new = {'id': 12, 'name': self.apk.name,
                    'browser_download_url': 'https://gitee.com/test/reader_123.apk'}

    def test_token_has_no_embedded_fallback(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, 'GITEE_TOKEN is required'):
                sync.token()

    def test_missing_release_is_available_for_creation(self):
        failure = urllib.error.HTTPError('https://gitee.com/api/v5/test', 404,
                                         'Not Found', {}, io.BytesIO(b'not found'))
        with patch.object(sync, 'token', return_value='synthetic'), \
                patch.object(sync.urllib.request, 'urlopen', side_effect=failure):
            self.assertIsNone(sync.gitee_get_release('new-release'))

    def test_release_authentication_error_is_not_treated_as_missing(self):
        failure = urllib.error.HTTPError('https://gitee.com/api/v5/test', 401,
                                         'Unauthorized', {}, io.BytesIO(b'unauthorized'))
        with patch.object(sync, 'token', return_value='synthetic'), \
                patch.object(sync.urllib.request, 'urlopen', side_effect=failure):
            with self.assertRaises(sync.ApiError) as raised:
                sync.gitee_get_release('new-release')
        self.assertEqual(raised.exception.status, 401)

    def test_http_errors_never_include_token_or_response_body(self):
        secret = 'synthetic-gitee-test-secret'
        url = 'https://gitee.com/api/v5/test?access_token=' + secret
        failure = urllib.error.HTTPError(url, 403, 'denied', {}, io.BytesIO(secret.encode()))
        with patch.dict(os.environ, {'GITEE_TOKEN': secret}), \
                patch.object(sync.urllib.request, 'urlopen', side_effect=failure):
            with self.assertRaises(RuntimeError) as raised:
                sync.request_json('GET', url)
        self.assertNotIn(secret, str(raised.exception))
        self.assertNotIn('access_token', str(raised.exception))
        self.assertIn('403', str(raised.exception))

    def test_auth_helper_reads_environment_without_saving_token(self):
        secret = 'synthetic-gitee-test-secret'
        with patch.dict(os.environ, {'GITEE_TOKEN': secret}):
            with sync.gitee_git_environment() as environment:
                wrapper = Path(environment['GIT_ASKPASS'])
                helper = wrapper.parent / 'askpass.py'
                self.assertNotIn(secret, helper.read_text(encoding='utf-8'))
                self.assertNotIn(secret, wrapper.read_text(encoding='utf-8'))
                self.assertEqual(environment['GIT_TERMINAL_PROMPT'], '0')
                result = subprocess.run([sys.executable, str(helper), 'Password for Gitee:'],
                                        env=environment, text=True, capture_output=True, check=True)
                self.assertEqual(result.stdout.strip(), secret)
            self.assertFalse(wrapper.exists())

    def fake_git(self, existing_sha=None):
        calls = []

        def run(args, check=True, env=None):
            calls.append(args)
            output, code = '', 0
            if args[0] == 'rev-parse':
                output = 'a' * 40 + '\n'
            if 'ls-remote' in args:
                if existing_sha is None:
                    code = 2
                else:
                    output = existing_sha + '\trefs/tags/archive-v14\n'
            return subprocess.CompletedProcess(args, code, output, '')

        return calls, run

    def test_conflicting_remote_tag_is_never_forced(self):
        calls, run = self.fake_git('b' * 40)
        with patch.object(sync, 'run_git', side_effect=run), \
                patch.object(sync, 'gitee_git_environment', return_value=contextlib.nullcontext({})):
            with self.assertRaisesRegex(RuntimeError, 'different object'):
                sync.ensure_gitee_tag('archive-v14')
        self.assertFalse(any('push' in args for args in calls))

    def test_new_tag_uses_public_source_without_credential_url_or_force(self):
        calls, run = self.fake_git()
        with patch.object(sync, 'run_git', side_effect=run), \
                patch.object(sync, 'gitee_git_environment', return_value=contextlib.nullcontext({})), \
                contextlib.redirect_stdout(io.StringIO()):
            sync.ensure_gitee_tag('archive-v14')
        fetch = next(args for args in calls if args[0] == 'fetch')
        self.assertIn('https://github.com/Kdkdmwnwdkd/-legado-build.git', fetch)
        push = next(args for args in calls if 'push' in args)
        self.assertIn('https://gitee.com/zziji/legado.git', push)
        self.assertIn('a' * 40 + ':refs/tags/archive-v14', push)
        self.assertFalse(any(arg in ('-f', '--force') for args in calls for arg in args))

    def test_failed_upload_keeps_previous_apk(self):
        with patch.object(sync, 'list_gitee_assets', return_value=[self.old]), \
                patch.object(sync, 'release_assets', return_value=[self.old]), \
                patch.object(sync, 'token', return_value='synthetic'), \
                patch.object(sync, 'request_json', side_effect=RuntimeError('upload failed')), \
                patch.object(sync, 'delete_old_apks') as delete:
            with self.assertRaisesRegex(RuntimeError, 'upload failed'):
                sync.upload_apks(42, [self.apk], replace_old=True)
        delete.assert_not_called()

    def test_failed_readback_keeps_previous_apk(self):
        with patch.object(sync, 'list_gitee_assets', return_value=[self.old]), \
                patch.object(sync, 'release_assets', side_effect=[[self.old], [self.old, self.new]]), \
                patch.object(sync, 'token', return_value='synthetic'), \
                patch.object(sync, 'request_json', return_value={}), \
                patch.object(sync, 'request_bytes', return_value=b'wrong APK'), \
                patch.object(sync, 'delete_old_apks') as delete, \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'readback differs'):
                sync.upload_apks(42, [self.apk], replace_old=True)
        delete.assert_not_called()

    def test_old_apk_is_deleted_only_after_verified_readback(self):
        events = []

        def readback(url):
            events.append('readback')
            return self.apk.read_bytes()

        def delete(*args):
            events.append('delete')

        with patch.object(sync, 'list_gitee_assets', return_value=[self.old]), \
                patch.object(sync, 'release_assets', side_effect=[[self.old], [self.old, self.new]]), \
                patch.object(sync, 'token', return_value='synthetic'), \
                patch.object(sync, 'request_json', return_value={}), \
                patch.object(sync, 'request_bytes', side_effect=readback), \
                patch.object(sync, 'delete_old_apks', side_effect=delete) as removal, \
                contextlib.redirect_stdout(io.StringIO()):
            sync.upload_apks(42, [self.apk], replace_old=True)
        self.assertEqual(events, ['readback', 'delete'])
        removal.assert_called_once_with(42, [self.old], {self.apk.name})

    def test_versioned_release_preserves_other_existing_assets(self):
        with patch.object(sync, 'list_gitee_assets', return_value=[self.old]), \
                patch.object(sync, 'release_assets', side_effect=[[self.old], [self.old, self.new]]), \
                patch.object(sync, 'token', return_value='synthetic'), \
                patch.object(sync, 'request_json', return_value={}), \
                patch.object(sync, 'request_bytes', return_value=self.apk.read_bytes()), \
                patch.object(sync, 'delete_old_apks') as delete, \
                contextlib.redirect_stdout(io.StringIO()):
            sync.upload_apks(42, [self.apk])
        delete.assert_not_called()

    def test_same_name_different_apk_is_refused_without_deletion_or_upload(self):
        with patch.object(sync, 'list_gitee_assets', return_value=[self.new]), \
                patch.object(sync, 'release_assets', return_value=[self.new]), \
                patch.object(sync, 'request_bytes', return_value=b'different APK'), \
                patch.object(sync, 'request_json') as upload, \
                patch.object(sync, 'delete_old_apks') as delete:
            with self.assertRaisesRegex(RuntimeError, 'readback differs'):
                sync.upload_apks(42, [self.apk], replace_old=True)
        upload.assert_not_called()
        delete.assert_not_called()

    def test_local_default_is_offline_preparation_without_git_or_credentials(self):
        output = io.StringIO()
        argv = [str(SCRIPT), '--tag', 'archive-v14', '--apk', str(self.apk)]
        with patch.object(sys, 'argv', argv), patch.dict(os.environ, {}, clear=True), \
                patch.object(sync, 'run_git') as git, \
                patch.object(sync, 'request_json') as remote, \
                contextlib.redirect_stdout(output):
            self.assertEqual(sync.main(), 0)
        git.assert_not_called()
        remote.assert_not_called()
        report = json.loads(output.getvalue()[output.getvalue().index('{'):])
        self.assertFalse(report['remoteWritesAttempted'])
        self.assertEqual(report['apks'][0]['sha256'], sync.apk_digest(self.apk))

    def test_github_asset_path_cannot_escape_download_directory(self):
        release = {'assets': [{'name': '../escape.apk', 'browser_download_url': 'https://github.com/test'}]}
        with patch.object(sync, 'request_bytes') as download:
            with self.assertRaisesRegex(RuntimeError, 'Invalid GitHub APK filename'):
                sync.download_github_apks(release, Path(self.directory.name))
        download.assert_not_called()


if __name__ == '__main__':
    unittest.main()
