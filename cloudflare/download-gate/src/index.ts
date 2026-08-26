interface Env {
  DOWNLOAD_KEY: string;
  APK_BUCKET: R2Bucket;
  R2_APK_KEY?: string;
  R2_METADATA_KEY?: string;
  APP_TITLE?: string;
}

interface ReleaseMetadata {
  tagName?: string;
  versionName?: string;
  versionCode?: number;
  updateLog?: string;
  downloadUrl?: string;
  fileName?: string;
  size?: number;
  sha256?: string;
  contentType?: string;
  updatedAt?: string;
}

const DEFAULT_APK_KEY = "legado-arm64-v8a.apk";
const DEFAULT_METADATA_KEY = "latest.json";
const APK_CONTENT_TYPE = "application/vnd.android.package-archive";
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

let cachedMetadata: { value: ReleaseMetadata; expiresAt: number } | null = null;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return htmlResponse(renderHome(env));
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return jsonResponse({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/latest") {
      return handleLatest(request, env);
    }

    if (
      (request.method === "POST" || request.method === "GET") &&
      url.pathname === "/download"
    ) {
      return handleDownload(request, env);
    }

    return new Response("Not found", { status: 404 });
  },
};

async function handleLatest(request: Request, env: Env): Promise<Response> {
  if (!env.DOWNLOAD_KEY) {
    return jsonResponse({ error: "Worker secret DOWNLOAD_KEY is required." }, 500);
  }

  if (!(await verifyDownloadKey(request, env))) {
    return jsonResponse({ error: "Invalid download key." }, 401);
  }

  const metadata = await getReleaseMetadata(env);
  const downloadUrl = new URL(request.url);
  downloadUrl.pathname = "/download";
  downloadUrl.search = "";

  return jsonResponse({
    ...metadata,
    downloadUrl: downloadUrl.toString(),
  });
}

async function handleDownload(request: Request, env: Env): Promise<Response> {
  if (!env.DOWNLOAD_KEY) {
    return jsonResponse({ error: "Worker secret DOWNLOAD_KEY is required." }, 500);
  }

  if (!(await verifyDownloadKey(request, env))) {
    return htmlResponse(renderHome(env, "Invalid download key."), 401);
  }

  const apkKey = env.R2_APK_KEY?.trim() || DEFAULT_APK_KEY;
  const object = await env.APK_BUCKET.get(apkKey);
  if (!object?.body) {
    return jsonResponse({ error: "APK object not found." }, 404);
  }

  const metadata = await getReleaseMetadata(env).catch(() => undefined);
  const fileName = sanitizeFilename(metadata?.fileName || apkKey.split("/").pop() || DEFAULT_APK_KEY);
  const size = metadata?.size || object.size;

  return new Response(object.body, {
    status: 200,
    headers: {
      "content-type": metadata?.contentType || object.httpMetadata?.contentType || APK_CONTENT_TYPE,
      "content-disposition": `attachment; filename="${fileName}"`,
      "cache-control": "private, no-store",
      "x-content-type-options": "nosniff",
      ...(metadata?.tagName ? { "x-release-tag": metadata.tagName } : {}),
      ...(metadata?.sha256 ? { "x-sha256": metadata.sha256 } : {}),
      ...(size > 0 ? { "content-length": String(size) } : {}),
    },
  });
}

async function getReleaseMetadata(env: Env): Promise<ReleaseMetadata> {
  const now = Date.now();
  if (cachedMetadata && cachedMetadata.expiresAt > now) {
    return cachedMetadata.value;
  }

  const apkKey = env.R2_APK_KEY?.trim() || DEFAULT_APK_KEY;
  const metadataKey = env.R2_METADATA_KEY?.trim() || DEFAULT_METADATA_KEY;
  const metadataObject = await env.APK_BUCKET.get(metadataKey);

  if (metadataObject) {
    const metadata = (await metadataObject.json()) as ReleaseMetadata;
    const normalized = normalizeMetadata(metadata, apkKey);
    cachedMetadata = { value: normalized, expiresAt: now + 15_000 };
    return normalized;
  }

  const apkObject = await env.APK_BUCKET.head(apkKey);
  if (!apkObject) {
    throw new Error(`R2 object not found: ${apkKey}`);
  }

  const normalized = normalizeMetadata(
    {
      fileName: apkKey.split("/").pop() || DEFAULT_APK_KEY,
      size: apkObject.size,
      contentType: apkObject.httpMetadata?.contentType || APK_CONTENT_TYPE,
      updatedAt: apkObject.uploaded?.toISOString(),
    },
    apkKey,
  );
  cachedMetadata = { value: normalized, expiresAt: now + 15_000 };
  return normalized;
}

function normalizeMetadata(metadata: ReleaseMetadata, apkKey: string): ReleaseMetadata {
  const fileName = metadata.fileName || apkKey.split("/").pop() || DEFAULT_APK_KEY;
  return {
    tagName: metadata.tagName || "",
    versionName: metadata.versionName || versionNameFromFileName(fileName) || "",
    versionCode: Number.isFinite(metadata.versionCode) ? metadata.versionCode : versionCodeFromFileName(fileName),
    updateLog: metadata.updateLog || (metadata.tagName ? `Private release ${metadata.tagName}` : "Private release"),
    fileName,
    size: metadata.size || 0,
    sha256: metadata.sha256 || "",
    contentType: metadata.contentType || APK_CONTENT_TYPE,
    updatedAt: metadata.updatedAt || "",
  };
}

function constantTimeEqual(left: string, right: string): boolean {
  const encoder = new TextEncoder();
  const leftBytes = encoder.encode(left);
  const rightBytes = encoder.encode(right);
  const maxLength = Math.max(leftBytes.length, rightBytes.length);
  let diff = leftBytes.length ^ rightBytes.length;

  for (let index = 0; index < maxLength; index += 1) {
    diff |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }

  return diff === 0;
}

async function verifyDownloadKey(request: Request, env: Env): Promise<boolean> {
  const url = new URL(request.url);
  let providedKey = request.headers.get("x-download-key") || url.searchParams.get("key") || "";
  if (!providedKey && request.method === "POST") {
    const form = await request.formData();
    providedKey = String(form.get("key") ?? "");
  }
  return constantTimeEqual(providedKey, env.DOWNLOAD_KEY);
}

function versionNameFromFileName(name: string): string | null {
  return /^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$/i.exec(name)?.[1] ?? null;
}

function versionCodeFromFileName(name: string): number {
  const raw = /^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$/i.exec(name)?.[2];
  return raw ? Number.parseInt(raw, 10) || 0 : 0;
}

function sanitizeFilename(name: string): string {
  return name.replace(/["\\\r\n]/g, "_");
}

function htmlResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

function renderHome(env: Env, error?: string): string {
  const title = escapeHtml(env.APP_TITLE || "Private APK Download");
  const apkKey = escapeHtml(env.R2_APK_KEY?.trim() || DEFAULT_APK_KEY);
  const errorHtml = error ? `<p class="error">${escapeHtml(error)}</p>` : "";

  return `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <style>
      :root {
        color-scheme: light dark;
        font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      }
      body {
        min-height: 100vh;
        margin: 0;
        display: grid;
        place-items: center;
        background: #f4f5f7;
        color: #16181d;
      }
      main {
        width: min(420px, calc(100vw - 32px));
        box-sizing: border-box;
        padding: 28px;
        border: 1px solid #d9dde5;
        border-radius: 8px;
        background: #ffffff;
        box-shadow: 0 16px 48px rgb(20 24 32 / 10%);
      }
      h1 {
        margin: 0 0 8px;
        font-size: 22px;
        line-height: 1.25;
        letter-spacing: 0;
      }
      .meta {
        margin: 0 0 24px;
        color: #5c6675;
        font-size: 13px;
        line-height: 1.5;
        overflow-wrap: anywhere;
      }
      label {
        display: block;
        margin-bottom: 8px;
        font-size: 14px;
        font-weight: 600;
      }
      input {
        width: 100%;
        height: 44px;
        box-sizing: border-box;
        border: 1px solid #b9c0cc;
        border-radius: 6px;
        padding: 0 12px;
        font: inherit;
        background: transparent;
      }
      button {
        width: 100%;
        height: 44px;
        margin-top: 16px;
        border: 0;
        border-radius: 6px;
        background: #1565c0;
        color: #fff;
        font: inherit;
        font-weight: 700;
        cursor: pointer;
      }
      button:hover {
        background: #0d56a8;
      }
      .error {
        margin: 0 0 16px;
        padding: 10px 12px;
        border-radius: 6px;
        background: #feecec;
        color: #a11212;
        font-size: 14px;
      }
      @media (prefers-color-scheme: dark) {
        body {
          background: #101318;
          color: #f2f4f8;
        }
        main {
          border-color: #303846;
          background: #181d24;
          box-shadow: none;
        }
        .meta {
          color: #aab4c2;
        }
        input {
          border-color: #586274;
          color: #f2f4f8;
        }
        .error {
          background: #3a1717;
          color: #ffb8b8;
        }
      }
    </style>
  </head>
  <body>
    <main>
      <h1>${title}</h1>
      <p class="meta">R2 object: ${apkKey}</p>
      ${errorHtml}
      <form method="post" action="/download">
        <label for="key">Download key</label>
        <input id="key" name="key" type="password" autocomplete="current-password" required autofocus>
        <button type="submit">Download ARM64 APK</button>
      </form>
    </main>
  </body>
</html>`;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
