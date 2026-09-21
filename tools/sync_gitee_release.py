#!/usr/bin/env python3
import argparse
from contextlib import contextmanager
import hashlib
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
import shlex
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path


GITHUB_REPO = "Kdkdmwnwdkd/-legado-build"
GITEE_OWNER = "zziji"
GITEE_REPO = "legado"
CHANNEL_TAG = "latest-arm64-release"
CHANNEL_NAME = "阅读 Archive 更新通道"


class ApiError(RuntimeError):
    def __init__(self, method, url, status):
        self.status = status
        super().__init__(f"{method} {safe_url(url)} failed: {status}")


def token():
    value = os.environ.get("GITEE_TOKEN", "").strip()
    if not value:
        raise RuntimeError("GITEE_TOKEN is required.")
    return value


def log(message):
    print(redact(message), flush=True)


def redact(message):
    value = str(message)
    for name in ("GITEE_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"):
        secret = os.environ.get(name, "").strip()
        if secret:
            value = value.replace(secret, "[redacted]")
    return value


def safe_url(url):
    parsed = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit((parsed.scheme, parsed.hostname or "", parsed.path, "", ""))


def run_git(args, check=True, env=None):
    result = subprocess.run(["git"] + args, text=True, encoding="utf-8", errors="replace",
                            capture_output=True, env=env)
    if check and result.returncode:
        raise RuntimeError("Git operation failed: " + redact(result.stderr.strip()))
    return result


@contextmanager
def gitee_git_environment():
    # Git obtains the password through askpass; it never appears in a remote URL.
    token()
    with tempfile.TemporaryDirectory(prefix="archive-gitee-auth-") as directory:
        helper = Path(directory) / "askpass.py"
        helper.write_text(
            "import os, sys\n"
            "prompt = ' '.join(sys.argv[1:]).lower()\n"
            "print('oauth2' if 'username' in prompt else os.environ['GITEE_TOKEN'])\n",
            encoding="utf-8")
        wrapper = Path(directory) / ("askpass.cmd" if os.name == "nt" else "askpass.sh")
        if os.name == "nt":
            wrapper.write_text(f'@echo off\n"{sys.executable}" "{helper}" %*\n', encoding="utf-8")
        else:
            wrapper.write_text(
                f'#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(helper))} "$@"\n',
                encoding="utf-8")
            wrapper.chmod(0o700)
        env = os.environ.copy()
        env.update(GIT_ASKPASS=str(wrapper), GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never")
        yield env


def request_json(method, url, data=None, headers=None):
    body = None
    headers = headers or {}
    if "api.github.com" in url:
        github_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
        if github_token:
            headers = {
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {github_token}",
                "X-GitHub-Api-Version": "2022-11-28",
                **headers,
            }
    if isinstance(data, dict):
        body = urllib.parse.urlencode(data).encode("utf-8")
        headers = {"Content-Type": "application/x-www-form-urlencoded", **(headers or {})}
    elif data is not None:
        body = data
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        # API error bodies and token query strings must not enter terminal logs.
        raise ApiError(method, url, error.code) from None
    except urllib.error.URLError:
        raise RuntimeError(f"{method} {safe_url(url)} failed: network error") from None
    if not raw:
        return None
    return json.loads(raw.decode("utf-8"))


def request_bytes(url):
    req = urllib.request.Request(url, headers={"User-Agent": "legado-gitee-sync"})
    last_error = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(req, timeout=300) as response:
                return response.read()
        except Exception as error:
            last_error = RuntimeError(f"Download failed: {safe_url(url)} ({type(error).__name__})")
            if attempt < 3:
                log(f"Download interrupted, retry {attempt}/3...")
                time.sleep(attempt * 2)
    raise last_error from None


def github_release(tag_name):
    if tag_name:
        url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/tags/{urllib.parse.quote(tag_name)}"
    else:
        url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
    return request_json("GET", url)


def gitee_api(path):
    return f"https://gitee.com/api/v5/repos/{GITEE_OWNER}/{GITEE_REPO}{path}"


def gitee_get_release(tag_name):
    query = urllib.parse.urlencode({"access_token": token()})
    try:
        return request_json("GET", f"{gitee_api('/releases/tags/' + urllib.parse.quote(tag_name))}?{query}")
    except ApiError as error:
        if error.status == 404:
            return None
        raise


def ensure_gitee_tag(tag_name, source_tag=None):
    source_tag = source_tag or tag_name
    ref = f"refs/tags/{tag_name}"
    run_git(["check-ref-format", ref])
    run_git(["check-ref-format", f"refs/tags/{source_tag}"])
    run_git(["fetch", "--no-tags", f"https://github.com/{GITHUB_REPO}.git", f"refs/tags/{source_tag}"])
    expected = run_git(["rev-parse", "FETCH_HEAD"]).stdout.strip()
    remote = f"https://gitee.com/{GITEE_OWNER}/{GITEE_REPO}.git"
    with gitee_git_environment() as env:
        current = run_git(["-c", "credential.helper=", "ls-remote", "--exit-code", "--refs", remote, ref],
                          check=False, env=env)
        if current.returncode == 0:
            if source_tag == tag_name and current.stdout.split()[0] != expected:
                raise RuntimeError("Existing Gitee tag points to a different object; no tag was changed.")
            log(f"Gitee tag already exists: {tag_name}")
            return
        if current.returncode != 2:
            raise RuntimeError("Unable to check Gitee tag: " + redact(current.stderr.strip()))
        run_git(["-c", "credential.helper=", "push", remote, f"{expected}:{ref}"], env=env)
    log(f"Pushed Gitee tag: {tag_name}")


def upsert_gitee_release(tag_name, release_name, body, target=None):
    release = gitee_get_release(tag_name)
    payload = {
        "access_token": token(),
        "tag_name": tag_name,
        "name": release_name,
        "body": body,
        "prerelease": "false",
    }
    if release and release.get("id"):
        release_id = release["id"]
        request_json("PATCH", gitee_api(f"/releases/{release_id}"), payload)
        return int(release_id)
    payload["target_commitish"] = target or tag_name
    created = request_json("POST", gitee_api("/releases"), payload)
    release_id = created.get("id") if isinstance(created, dict) else None
    if not release_id:
        raise RuntimeError(f"Unable to create Gitee release for {tag_name}")
    return int(release_id)


def list_gitee_assets(release_id):
    query = urllib.parse.urlencode({"access_token": token()})
    return request_json("GET", f"{gitee_api(f'/releases/{release_id}/attach_files')}?{query}") or []


def delete_old_apks(release_id, previous_assets, keep_names):
    query = urllib.parse.urlencode({"access_token": token()})
    for asset in previous_assets:
        name = str(asset.get("name", ""))
        asset_id = asset.get("id")
        if asset_id and name.endswith(".apk") and name not in keep_names:
            request_json("DELETE", f"{gitee_api(f'/releases/{release_id}/attach_files/{asset_id}')}?{query}")
            log(f"Deleted old Gitee APK: {name}")


def multipart(fields, files):
    boundary = f"----legado-sync-{uuid.uuid4().hex}"
    chunks = []
    for key, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode())
        chunks.append(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode())
        chunks.append(str(value).encode("utf-8"))
        chunks.append(b"\r\n")
    for key, path in files.items():
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        chunks.append(f"--{boundary}\r\n".encode())
        chunks.append(
            (
                f'Content-Disposition: form-data; name="{key}"; filename="{path.name}"\r\n'
                f"Content-Type: {mime}\r\n\r\n"
            ).encode()
        )
        chunks.append(path.read_bytes())
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), boundary


def apk_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verified_asset(asset, apk):
    url = asset.get("browser_download_url")
    parsed = urllib.parse.urlsplit(url or "")
    if parsed.scheme != "https" or parsed.hostname != "gitee.com" or parsed.username:
        raise RuntimeError("Gitee APK has no valid download URL.")
    data = request_bytes(url)
    if len(data) != apk.stat().st_size or hashlib.sha256(data).hexdigest() != apk_digest(apk):
        raise RuntimeError("Gitee APK readback differs from local file: " + apk.name)


def release_assets(release_id):
    query = urllib.parse.urlencode({"access_token": token()})
    release = request_json("GET", f"{gitee_api(f'/releases/{release_id}')}?{query}")
    return release.get("assets") or []


def upload_apks(release_id, apks, replace_old=False):
    previous_assets = list_gitee_assets(release_id)
    existing = {asset.get("name"): asset for asset in release_assets(release_id)}
    for apk in apks:
        if apk.name in existing:
            verified_asset(existing[apk.name], apk)
            log(f"Already present and verified: {apk.name}")
            continue
        body, boundary = multipart({"access_token": token()}, {"file": apk})
        request_json(
            "POST",
            gitee_api(f"/releases/{release_id}/attach_files"),
            data=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        log(f"Uploaded to Gitee: {apk.name}")
    uploaded = {asset.get("name"): asset for asset in release_assets(release_id)}
    for apk in apks:
        if apk.name not in uploaded:
            raise RuntimeError("Uploaded APK is missing from Gitee release: " + apk.name)
        verified_asset(uploaded[apk.name], apk)
    # Failed or interrupted uploads leave all previously working APKs available.
    if replace_old:
        delete_old_apks(release_id, previous_assets, {apk.name for apk in apks})


def download_github_apks(release, directory):
    apks = []
    for asset in release.get("assets") or []:
        name = str(asset.get("name", ""))
        url = asset.get("browser_download_url")
        if not name.endswith(".apk") or not url:
            continue
        if name != Path(name).name or "/" in name or "\\" in name or any(ord(c) < 32 for c in name):
            raise RuntimeError("Invalid GitHub APK filename.")
        target = directory / name
        log(f"Downloading GitHub asset: {name}")
        target.write_bytes(request_bytes(url))
        apks.append(target)
    if not apks:
        raise RuntimeError(f"No APK assets found in GitHub release {release.get('tag_name')}")
    return apks


def verify_gitee_release(tag_name):
    release = gitee_get_release(tag_name)
    assets = release.get("assets") if release else None
    apk_urls = [
        asset.get("browser_download_url", "")
        for asset in assets or []
        if str(asset.get("name", "")).endswith(".apk")
    ]
    if not apk_urls:
        raise RuntimeError(f"Gitee release has no APK assets: {tag_name}")
    bad_urls = [url for url in apk_urls if urllib.parse.urlsplit(url).hostname != "gitee.com"]
    if bad_urls:
        raise RuntimeError("Gitee release contains invalid APK download URLs.")
    log(f"Verified Gitee release {tag_name}: {len(apk_urls)} APK asset(s)")


def main():
    parser = argparse.ArgumentParser(description="Sync GitHub release APKs to Gitee locally.")
    parser.add_argument("--tag", help="GitHub release tag. Empty means latest GitHub release.")
    parser.add_argument("--apk", action="append", help="Local APK path to upload instead of downloading from GitHub.")
    parser.add_argument("--release-name", help="Release name for local APK upload mode.")
    parser.add_argument("--body-file", help="Release notes file for local APK upload mode.")
    parser.add_argument("--skip-channel", action="store_true", help="Do not sync latest-arm64-release.")
    parser.add_argument("--publish", action="store_true", help="Write to Gitee. Without this flag, only prepare and report.")
    args = parser.parse_args()

    local_apks = [Path(path) for path in args.apk or []]
    if local_apks:
        if not args.tag:
            raise RuntimeError("--tag is required when --apk is used.")
        missing = [str(path) for path in local_apks if not path.is_file()]
        if missing:
            raise RuntimeError(f"Local APK does not exist: {missing}")
        tag_name = args.tag
        release_name = args.release_name or tag_name
        body = Path(args.body_file).read_text(encoding="utf-8") if args.body_file else ""
        apks = local_apks
        log(f"Local APK release: {tag_name} / {release_name}")
    else:
        release = github_release(args.tag)
        tag_name = release["tag_name"]
        release_name = release.get("name") or tag_name
        body = release.get("body") or ""
        apks = None
        log(f"GitHub release: {tag_name} / {release_name}")

    with tempfile.TemporaryDirectory(prefix="legado-gitee-sync-") as tmp:
        if apks is None:
            apks = download_github_apks(release, Path(tmp))
        if len({apk.name for apk in apks}) != len(apks):
            raise RuntimeError("APK filenames must be unique.")
        for apk in apks:
            if apk.suffix.lower() != ".apk" or not zipfile.is_zipfile(apk):
                raise RuntimeError("Not an APK archive: " + apk.name)
            with zipfile.ZipFile(apk) as archive:
                if "AndroidManifest.xml" not in archive.namelist():
                    raise RuntimeError("APK manifest is missing: " + apk.name)
        if not args.publish:
            log(json.dumps({"status": "prepared", "remoteWritesAttempted": False,
                "githubRepository": GITHUB_REPO, "giteeRepository": f"{GITEE_OWNER}/{GITEE_REPO}",
                "tag": tag_name, "updateChannel": not args.skip_channel,
                "apks": [{"name": apk.name, "size": apk.stat().st_size, "sha256": apk_digest(apk)} for apk in apks]},
                ensure_ascii=False, indent=2))
            return 0
        token()
        ensure_gitee_tag(tag_name)
        current = gitee_get_release(tag_name)
        versioned_id = int(current["id"]) if current else upsert_gitee_release(tag_name, release_name, "")
        upload_apks(versioned_id, apks)
        upsert_gitee_release(tag_name, release_name, body)
        verify_gitee_release(tag_name)

        if not args.skip_channel:
            channel = gitee_get_release(CHANNEL_TAG)
            if channel is None:
                ensure_gitee_tag(CHANNEL_TAG, source_tag=tag_name)
            channel_id = int(channel["id"]) if channel else upsert_gitee_release(CHANNEL_TAG, CHANNEL_NAME, "", CHANNEL_TAG)
            upload_apks(channel_id, apks, replace_old=True)
            upsert_gitee_release(CHANNEL_TAG, CHANNEL_NAME, body, CHANNEL_TAG)
            verify_gitee_release(CHANNEL_TAG)

    log("Gitee release sync finished.")
    return 0


if __name__ == "__main__":
    if not shutil.which("git"):
        print("git is required.", file=sys.stderr)
        sys.exit(1)
    try:
        sys.exit(main())
    except Exception as error:
        print(redact(error), file=sys.stderr)
        sys.exit(1)
