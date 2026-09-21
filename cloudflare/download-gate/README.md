# Legado Download Gate

Cloudflare Worker download gate for the private ARM64 APK stored in Cloudflare R2.

The Worker checks `DOWNLOAD_KEY`, reads update metadata from R2, and streams the APK from R2. GitHub Release can remain as a backup record, but downloads no longer depend on GitHub.

## R2 Objects

CircleCI writes these fixed objects on every release build:

- `legado-arm64-v8a.apk`
- `latest.json`

The APK key is overwritten on every build to avoid accumulating old APK files.

## Configure

`wrangler.toml` binds the R2 bucket:

```toml
[[r2_buckets]]
binding = "APK_BUCKET"
bucket_name = "mybugget"
```

Store the download password as a Worker secret:

```powershell
cd cloudflare/download-gate
npm install
npx wrangler secret put DOWNLOAD_KEY
```

## Run Locally

Create `.dev.vars` for local testing only:

```text
DOWNLOAD_KEY=your-download-key
```

Then start the Worker:

```powershell
npm run dev
```

## Deploy

```powershell
npm run deploy
```

## API

The Android app can use the Worker root URL as its internal beta update address:

- `GET /latest` with header `X-Download-Key: <DOWNLOAD_KEY>` returns metadata.
- `GET /download` with header `X-Download-Key: <DOWNLOAD_KEY>` streams the APK.

The web form at `/` sends the key via `POST /download`.
