# 阅读Shimmer 下载网关

Cloudflare Worker 下载网关，用于从 Cloudflare R2 分发私有 APK。

Worker 校验 `DOWNLOAD_KEY`，从 R2 读取更新元数据并流式返回 APK。这是 APK 里**「内部测试版更新」通道**的服务端：在设置里填入 Worker 地址与下载密码后，客户端会带 `X-Download-Key` 请求 `/latest`，与 GitHub Release 并行检查，取版本号更高的那个（见 `AppUpdate.kt`）。

## R2 对象

两个固定对象：

- `legado-arm64-v8a.apk` — APK 本体
- `latest.json` — 更新元数据

> 对象名由 `wrangler.toml` 的 `R2_APK_KEY` / `R2_METADATA_KEY` 指定，代码里的保底值见 `src/index.ts` 的 `DEFAULT_APK_KEY`。改这个值必须同时改 `wrangler.toml`，否则 Worker 找不到对象。

**目前没有任何自动化流程写这两个对象**，每次发版需手动上传。APK 键每次覆盖，不累积历史文件。

### 手动上传

```bash
npx wrangler r2 object put <bucket>/legado-arm64-v8a.apk \
  --file ./shimmer-arm64-v8a_app_<版本名>_<版本号>.apk

npx wrangler r2 object put <bucket>/latest.json --file ./latest.json
```

`latest.json` 的字段（全部可选，缺省时按下面的规则回落）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `versionName` | string | 版本名，缺省时从 `fileName` 解析 |
| `versionCode` | number | 版本号，缺省时从 `fileName` 解析 |
| `tagName` | string | 发布标签，用于拼默认更新说明 |
| `updateLog` | string | 更新说明正文 |
| `fileName` | string | 下载时的文件名，缺省为 APK 键的文件名部分 |
| `size` | number | 字节数 |
| `sha256` | string | 校验值 |
| `contentType` | string | 缺省为 `application/vnd.android.package-archive` |
| `updatedAt` | string | ISO 时间戳 |

**APK 文件名必须保持 `<任意>_<任意>_<版本名>_<版本号>.apk`**：`latest.json` 缺省 `versionName` / `versionCode` 时，Worker 靠这个格式从文件名反解。目前 `android-fast-release.yml` 产出的 `shimmer-arm64-v8a_app_<版本>_<版本号>.apk` 符合该格式。

元数据在 Worker 内存里缓存 15 秒，更新 `latest.json` 后最多等 15 秒生效。

## 配置

`wrangler.toml` 绑定 R2 桶：

```toml
[[r2_buckets]]
binding = "APK_BUCKET"
bucket_name = "mybugget"
```

下载密码存为 Worker secret：

```powershell
cd cloudflare/download-gate
npm install
npx wrangler secret put DOWNLOAD_KEY
```

## 本地运行

只在本地测试时创建 `.dev.vars`：

```text
DOWNLOAD_KEY=your-download-key
```

然后启动 Worker：

```powershell
npm run dev
```

## 部署

```powershell
npm run deploy
```

改过 `wrangler.toml` 的 `[vars]`（例如 `APP_TITLE`）需要重新部署才会生效。

## API

- `GET /` — 带密码输入框的下载页（`APP_TITLE` 为页面标题）
- `GET /health` — 存活检查，返回 `{"ok":true}`
- `GET /latest`（需 `X-Download-Key`）— 返回元数据
- `GET /download`（需 `X-Download-Key`）— 流式返回 APK
- `POST /download` — 下载页表单提交，密码在请求体中

## 与 GitHub Release 的关系

两者是并列的两个来源，不是主备：

- GitHub Release 由 `android-fast-release.yml` 自动发布，是公开渠道
- 本网关是私有渠道，内容手动上传，用于放尚未公开的构建

客户端同时检查两者，取版本号更高的一个。任一方不可用不影响另一方。
