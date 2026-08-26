#!/usr/bin/env python3
import argparse
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


GITHUB_REPO = "Rimchars/legado"
GITEE_OWNER = "zziji"
GITEE_REPO = "legado"
CHANNEL_TAG = "latest-arm64-release"
DEFAULT_GITEE_TOKEN = "fa21c7647612f4ccc0101e13c786bfd4"
CHANNEL_NAME = "阅读 Archive 更新通道"


def token():
    value = os.environ.get("GITEE_TOKEN", DEFAULT_GITEE_TOKEN).strip()
    if not value:
        raise RuntimeError("GITEE_TOKEN is required.")
    return value


def log(message):
    print(message, flush=True)


def run_git(args, check=True):
    return subprocess.run(["git"] + args, text=True, check=check)


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
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed: {error.code} {detail}") from error
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
            last_error = error
            if attempt < 3:
                log(f"Download interrupted, retry {attempt}/3...")
                time.sleep(attempt * 2)
    raise last_error


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
    except RuntimeError as error:
        if " 404 " in str(error) or "Not Found" in str(error):
            return None
        raise


def ensure_gitee_tag(tag_name):
    remote = f"https://oauth2:{token()}@gitee.com/{GITEE_OWNER}/{GITEE_REPO}.git"
    run_git(["fetch", "origin", f"refs/tags/{tag_name}:refs/tags/{tag_name}"], check=False)
    exists = subprocess.run(
        ["git", "ls-remote", "--exit-code", "--tags", remote, f"refs/tags/{tag_name}"],
        text=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0
    if exists:
        log(f"Gitee tag already exists: {tag_name}")
        return
    run_git(["push", remote, f"refs/tags/{tag_name}:refs/tags/{tag_name}"])
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


def delete_old_apks(release_id):
    query = urllib.parse.urlencode({"access_token": token()})
    for asset in list_gitee_assets(release_id):
        name = str(asset.get("name", ""))
        asset_id = asset.get("id")
        if asset_id and name.endswith(".apk"):
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


def upload_apks(release_id, apks):
    delete_old_apks(release_id)
    for apk in apks:
        body, boundary = multipart({"access_token": token()}, {"file": apk})
        request_json(
            "POST",
            gitee_api(f"/releases/{release_id}/attach_files"),
            data=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        log(f"Uploaded to Gitee: {apk.name}")


def download_github_apks(release, directory):
    apks = []
    for asset in release.get("assets") or []:
        name = str(asset.get("name", ""))
        url = asset.get("browser_download_url")
        if not name.endswith(".apk") or not url:
            continue
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
    bad_urls = [url for url in apk_urls if "gitee.com" not in url]
    if bad_urls:
        raise RuntimeError(f"Gitee release contains non-Gitee APK urls: {bad_urls}")
    log(f"Verified Gitee release {tag_name}: {len(apk_urls)} APK asset(s)")


def main():
    parser = argparse.ArgumentParser(description="Sync GitHub release APKs to Gitee locally.")
    parser.add_argument("--tag", help="GitHub release tag. Empty means latest GitHub release.")
    parser.add_argument("--apk", action="append", help="Local APK path to upload instead of downloading from GitHub.")
    parser.add_argument("--release-name", help="Release name for local APK upload mode.")
    parser.add_argument("--body-file", help="Release notes file for local APK upload mode.")
    parser.add_argument("--skip-channel", action="store_true", help="Do not sync latest-arm64-release.")
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
        run_git(["tag", "-f", tag_name])
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
        ensure_gitee_tag(tag_name)
        versioned_id = upsert_gitee_release(tag_name, release_name, body)
        upload_apks(versioned_id, apks)
        verify_gitee_release(tag_name)

        if not args.skip_channel:
            if gitee_get_release(CHANNEL_TAG) is None:
                run_git(["tag", "-f", CHANNEL_TAG, tag_name])
                ensure_gitee_tag(CHANNEL_TAG)
            channel_id = upsert_gitee_release(CHANNEL_TAG, CHANNEL_NAME, body, CHANNEL_TAG)
            upload_apks(channel_id, apks)
            verify_gitee_release(CHANNEL_TAG)

    log("Gitee release sync finished.")
    return 0


if __name__ == "__main__":
    if not shutil.which("git"):
        print("git is required.", file=sys.stderr)
        sys.exit(1)
    sys.exit(main())
