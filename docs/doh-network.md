# 网络与 DNS

入口为“我的 → 其它设置 → 网络与 DNS”，沿用应用的 Compose 设置行、开关和对话框。默认关闭 DoH；配置保存后重启应用生效，避免正文、图片、WebSocket 等长期持有的客户端各用一套旧设置。

## 使用方式

1. 选择阿里、腾讯 DNSPod、Cloudflare、Google 或自定义 HTTPS 服务。
2. 选择需要使用 DoH 的功能，必要时添加精确域名或 `*.example.com` 规则。
3. 在页面内测速后选择服务，重启并应用。测速不会自动替换服务或打开总开关。

分流包含书源/搜索/正文、图片、AI、HTTP 朗读/音视频、脚本 WebSocket、云同步、在线导入、中继、其它原生请求。规则适用于客户端新建连接时实际需要解析的主机，不能让已有连接重新执行 DNS。

嵌套的 `java.ajax/connect` 和远程 `jsLib` 下载继承当前功能；同步的 `java.get/head/post` 在创建 URLConnection 时带入所属分类，调用结束后恢复线程状态，不影响后续请求。

解析优先级是：原始请求主机匹配的书源 `dnsIp` → 自定义 Hosts → 域名例外 → 功能选项。精确域名优先于通配符，更长的通配后缀优先；通配符不包括根域名。DoH 总开关关闭时，域名例外也不会启用 DoH。默认局域网名称走系统，可以用显式规则覆盖。

## 覆盖边界

- HTTP/SOCKS 代理通常负责目标域名解析，本地最多解析代理主机，不能宣称目标也经过应用 DoH。显式代理不再被 Cronet 绕过；有代理时由代理处理目标，书源 `dnsIp` 不覆盖代理主机。
- DoH 或 Hosts 需要介入的功能使用 OkHttp。存在 DoH 域名例外时，相关客户端的重定向也由 OkHttp 处理，避免 Cronet 绕过后续域名规则。其它允许的请求仍可使用 Cronet。
- WebView 自己发起的登录页、子资源、外部脚本请求，以及系统/第三方 TTS 不受原生客户端分流控制。应用明确接管的主文档或图片请求按其功能处理。
- 在线导入保留 `GuardedImportDns` 全地址检查、每次下载的独立连接池、禁用代理、实际连接地址检查和逐跳重定向检查，DoH 不取消原有私网确认。
- 系统 DNS 仍可受到 Android 私人 DNS、VPN 和系统缓存影响。自定义服务未填写启动 IP 时，连接 DoH 服务本身可能先使用系统 DNS；系统代理主机也可能需要系统解析。

## 稳定性与测速

DoH 使用与现有 OkHttp 同版本的官方模块。客户端独立创建，保留默认 TLS 证书链及主机名校验，不继承业务客户端的宽松证书校验、Cookie 或 Cronet。启动 IP 只决定连接地址，URL、Host 与证书域名保持原样。

使用独立调度器和连接池、4 MiB HTTP 缓存，以及 64 KiB 的解压后响应上限。缓存遵循 HTTP 缓存语义；重复的并发域名查询共享当前查询，不额外保存没有 TTL 的结果。请求、入队和等待设置时限。DoH 失败默认报错，只有用户打开回退时才查询系统 DNS。

测速直接使用生产解析实现。对系统 DNS 和四个预设服务分别查询三次，并展示首次耗时、成功次数、中位数、地址数量及失败原因。DoH 测速不使用本地 HTTP 缓存、Hosts、功能分流或系统回退；复测可以复用 TLS 连接，系统缓存仍由系统控制。这是解析耗时，不是下载带宽或正文渲染性能。

设置搜索保留旧 `customHosts` 与 `Cronet` 键，入口合并到本页面。普通偏好备份可携带新的完整配置；旧备份没有此项时沿用已有设置。无效或不支持版本的配置不会导致启动崩溃，会使用系统默认并在设置页面提示重新保存。

## 验证记录

本次验证记录统一放在 `output/doh-network-*`，与上一版发布材料分开。JVM 回归覆盖 DNS 策略、真实 HTTPS DNS 报文、缓存、TLS、响应限额和导入保护；APK 检查包含版本、签名、ARM64、资源及 DNS 类的 DEX 引用。实际测速来自 Windows 构建机器，设备上的网络表现应以设置内测速为准。

候选版本 `3.26.09120802-doh` 的最终 JVM 回归记录为 `output/doh-network-test-3.json`：共 784 项，0 失败、0 错误、2 项既有测试跳过。源码在执行期间保持一致，最终差异归档在 `output/doh-network-source-final.patch`。

完整 Lint 已通过，记录为 `output/doh-network-lint-2.json`：无新增错误，原有 142 项错误基线未扩大。共 1529 条警告，其中 1524 条与上一版一致；新增的 5 条已逐项审查：三个 OkHttp 模块的可选升级提示、旧 `json_format` 文案未使用，以及需要读取 `commit()` 成功结果而保留原生 SharedPreferences 写法的 KTX 建议。发布检查仅接受这些具体消息及对应文件，额外或重复警告仍会失败；审查记录见 `output/doh-network-lint-review.json`。

2026-09-12 在 Windows 构建机查询 `example.com` 的实测如下，开启 A/AAAA 查询；DoH 本地 HTTP 缓存、Hosts 和系统回退均关闭，复测可复用 TLS 连接，系统 DNS 缓存由操作系统控制。

| 服务 | 成功次数 | 首次耗时 | 三次中位耗时 |
| --- | --- | --- | --- |
| 系统 DNS | 3/3 | 29.6 ms | 2.2 ms |
| 阿里 DNS | 3/3 | 446.8 ms | 12.0 ms |
| 腾讯 DNSPod | 3/3 | 205.3 ms | 29.0 ms |
| Cloudflare | 0/3 | 超时 | 无成功结果 |
| Google | 0/3 | 超时 | 无成功结果 |

完整样本见 `output/doh-network-measurements-1.json`。首次耗时包含连接建立；这些结果只代表本次网络和目标域名，不能据此保证其它网络下的速度或可达性。

签名 APK 已生成并通过实际文件校验：`output/doh-network-3.26.09120802-doh-29819522-arm64-v8a-candidate.apk`，大小为 41,041,347 字节，SHA-256 为 `df1d4f143dd0a3e7ce8433f9d13c88bdaaa134a344128c6e34d374573aecd971`。包名为 `io.legado.app.Archive`，仅含 ARM64 库，签名证书与上一版一致；所有 EPUB 资源、DoH 类及其 DEX 引用均已检查。构建及验包记录分别为 `output/doh-network-assemble-1.json` 和 `output/doh-network-apk-bytes-verification.json`；汇总验证记录为 `output/doh-network-release-verification.json`。

目前没有连接 Android 真机，不能把 JVM、主机网络测速和静态 APK 检查当作 ART、厂商 WebView、Wi-Fi/移动网络/VPN 切换的真机验收。
