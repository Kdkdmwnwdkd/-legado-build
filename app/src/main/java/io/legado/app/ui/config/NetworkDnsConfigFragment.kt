package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.dns.DnsBenchmark
import io.legado.app.help.http.dns.DnsDomainRule
import io.legado.app.help.http.dns.DnsHosts
import io.legado.app.help.http.dns.DnsNames
import io.legado.app.help.http.dns.DnsProbeResult
import io.legado.app.help.http.dns.DnsScope
import io.legado.app.help.http.dns.DohProvider
import io.legado.app.help.http.dns.NetworkDnsConfig
import io.legado.app.help.http.dns.NetworkDnsStore
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.restart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.Locale

/** The same page renderer, preferences, dialogs, theme and search navigation as
 * the other settings screens; no second UI component system. */
class NetworkDnsConfigFragment : ComposeSettingFragment() {
    override val titleRes: Int = R.string.network_dns_title
    override val autoOpenTargetItem: Boolean = false
    private var benchmarkJob: Job? = null
    private var benchmarkGeneration = 0L
    private var results = emptyList<DnsProbeResult>()
    private var progress = ""
    private var lastError: String? = null
    private var applyingSettings = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (NetworkDnsStore.hasInvalidSettings(requireContext())) lastError = "已保存的 DNS 配置无效，当前使用系统 DNS，请重新保存配置。"
    }

    override fun onDestroyView() {
        cancelBenchmark()
        super.onDestroyView()
    }

    private fun cancelBenchmark() {
        benchmarkGeneration++
        benchmarkJob?.cancel()
        benchmarkJob = null
        progress = ""
    }

    override fun buildPageSpec(): SettingPageSpec {
        val config = NetworkDnsStore.load(requireContext())
        val selected = DohProvider.presets.find { it.url == config.endpoint && it.bootstrap == config.bootstrap }
        val running = benchmarkJob?.isActive == true
        val sections = mutableListOf(
            SettingSectionSpec(items = listOf(
                SettingSwitchSpec(PreferKey.networkDnsConfig, "启用 DoH", config.enabled,
                    { save(config.copy(enabled = it)) }, "保存后重启生效；未启用时跟随系统 DNS"),
                action("dns_provider", "DoH 服务", selected?.name ?: "自定义服务", ::chooseProvider),
                action("dns_custom", "服务地址与启动 IP", selected?.url ?: "已保存自定义 HTTPS 地址", ::editEndpoint),
                action("dns_restart", "重启并应用", lastError ?: "分流规则、Hosts 和网络组件在重启后一起生效") {
                    showComposeConfirmDialog("重启应用", "已保存的网络设置将在重启后生效。", onPositive = ::restartWithSavedSettings)
                }
            )),
            SettingSectionSpec(title = "按功能选择", items = DnsScope.entries.map { scope ->
                SettingSwitchSpec("dns_scope_${scope.key}", scope.title, scope in config.scopes,
                    { checked -> save(config.copy(scopes = if (checked) config.scopes + scope else config.scopes - scope)) },
                    if (scope in config.scopes) "DoH 开启后使用 DoH" else "使用系统 DNS")
            }),
            SettingSectionSpec(title = "域名例外", items = listOf(
                action("dns_rule_add", "添加域名规则", "可为域名指定 DoH 或系统 DNS，优先于功能选择") { editRule(null) }
            ) + config.rules.map { rule ->
                action("dns_rule_${rule.pattern}", rule.pattern, if (rule.doh) "使用 DoH" else "使用系统 DNS") { editRule(rule) }
            }),
            SettingSectionSpec(title = "DNS 测速", items = buildList {
                add(action("dns_test_host", "测试域名", stringSetting(TEST_HOST, "example.com")) { editTestHost() })
                add(action("dns_benchmark", if (running) "停止测速" else "开始测速",
                    if (running) progress else "系统 DNS 与各 DoH 服务各查询 3 次，只比较解析耗时") {
                    if (running) { cancelBenchmark(); refreshSettings() } else startBenchmark()
                })
                results.forEach { result ->
                    add(action("dns_result_${result.provider?.id ?: "system"}", result.name,
                        "成功 ${result.successCount}/${result.samples.size} · 首次 ${ms(result.firstMilliseconds)} · 中位数 ${ms(result.medianMilliseconds)}") {
                        showResult(result)
                    })
                }
            }),
            SettingSectionSpec(title = "高级", items = listOf(
                SettingSwitchSpec("dns_ipv6", "查询 IPv6 地址", config.includeIpv6, { save(config.copy(includeIpv6 = it)) }, "同时查询 A 和 AAAA；系统 DNS 由系统决定"),
                SettingSwitchSpec("dns_local", "局域网名称使用系统 DNS", config.localNamesUseSystem, { save(config.copy(localNamesUseSystem = it)) }, "单标签域名、localhost、.local、.lan 等；域名例外可覆盖"),
                SettingSwitchSpec("dns_fallback", "DoH 失败时回退系统 DNS", config.fallbackToSystem, { save(config.copy(fallbackToSystem = it)) }, "关闭时解析失败会报错；测速始终不回退"),
                action(PreferKey.customHosts, "自定义 Hosts", "指定地址优先于 DoH 和域名规则", ::editHosts),
                SettingSwitchSpec(PreferKey.cronet, "优先使用 Cronet", booleanSetting(PreferKey.cronet, false),
                    { updateBooleanSetting(PreferKey.cronet, it) }, "DoH、Hosts、指定 IP 或显式代理请求自动使用 OkHttp；重启后生效"),
                action("dns_help", "覆盖范围与测速说明", "WebView、代理、书源指定 IP 和系统 DNS 的区别", ::showHelp)
            ))
        )
        return SettingPageSpec(titleRes, sections)
    }

    private fun action(key: String, title: String, summary: String, click: () -> Unit): SettingItemSpec =
        SettingActionSpec(key = key, title = title, summary = summary, onClick = click)

    private fun save(config: NetworkDnsConfig) {
        runCatching { NetworkDnsStore.save(requireContext(), config) }
            .onSuccess { lastError = null }
            .onFailure { lastError = it.message ?: "DNS 配置无效" }
        refreshSettings()
    }

    private fun restartWithSavedSettings() {
        if (applyingSettings) return
        applyingSettings = true
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { NetworkDnsStore.persistBeforeRestart(context) }
                if (saved) context.restart() else {
                    lastError = "设置未能写入，请稍后重试。"
                    refreshSettings()
                }
            } finally { applyingSettings = false }
        }
    }

    private fun chooseProvider() {
        val config = NetworkDnsStore.load(requireContext())
        showComposeChoiceListDialog("DoH 服务", DohProvider.presets.map { it.name } + "自定义",
            selectedIndex = DohProvider.presets.indexOfFirst { it.url == config.endpoint && it.bootstrap == config.bootstrap }
                .let { if (it < 0) DohProvider.presets.size else it },
            onSelected = { index ->
                DohProvider.presets.getOrNull(index)?.let { save(config.copy(endpoint = it.url, bootstrap = it.bootstrap)) } ?: editEndpoint()
            })
    }

    private fun editEndpoint() {
        val config = NetworkDnsStore.load(requireContext())
        fun candidate(values: List<String>): NetworkDnsConfig = config.copy(endpoint = values[0].trim(),
            bootstrap = values[1].split(Regex("[,，\\s]+")).filter { it.isNotBlank() })
        showComposeTextFormDialog("DoH 服务", labels = listOf("HTTPS 查询地址", "启动 IP（可选，多个用逗号分隔）"),
            initialValues = listOf(config.endpoint, config.bootstrap.joinToString(", ")),
            message = "启动 IP 用于连接 DoH 服务，仍校验原域名证书；留空时可能先由系统解析服务域名。",
            validateInput = { values -> runCatching { candidate(values).validated() }.isSuccess },
            onPositive = { save(candidate(it)) })
    }

    private fun editRule(original: DnsDomainRule?) {
        showComposeTextInputDialog("域名规则", hint = "example.com 或 *.example.com", initialValue = original?.pattern.orEmpty(),
            message = "精确域名优先；通配符只匹配子域名。DoH 总开关关闭时全部跟随系统。",
            validateInput = { runCatching { DnsDomainRule(it, false).normalized() }.isSuccess },
            neutralText = if (original == null) null else "删除规则",
            onNeutral = if (original == null) null else { {
                val config = NetworkDnsStore.load(requireContext())
                save(config.copy(rules = config.rules.filterNot { it.pattern == original.pattern }))
            } },
            onPositive = { pattern ->
                showComposeChoiceListDialog("${pattern.trim()} 使用", listOf("DoH", "系统 DNS"),
                    selectedIndex = if (original?.doh != false) 0 else 1,
                    onSelected = { index ->
                        val rule = DnsDomainRule(pattern, index == 0).normalized()
                        val config = NetworkDnsStore.load(requireContext())
                        save(config.copy(rules = config.rules.filterNot { it.pattern == original?.pattern || it.pattern == rule.pattern } + rule))
                    })
            })
    }

    private fun editHosts() {
        showComposeTextInputDialog("自定义 Hosts", initialValue = stringSetting(PreferKey.customHosts, ""),
            hint = "{\"example.com\": [\"192.0.2.1\"]}", minLines = 4, maxLines = 12,
            message = "保存后重启生效，保留已有 Hosts 格式。",
            validateInput = { runCatching { DnsHosts.parse(it, strict = true) }.isSuccess },
            onPositive = { updateStringSetting(PreferKey.customHosts, it.trim()) })
    }

    private fun editTestHost() {
        showComposeTextInputDialog("测试域名", initialValue = stringSetting(TEST_HOST, "example.com"),
            validateInput = { DnsNames.host(it)?.let { host -> DnsNames.literal(host) == null } == true },
            onPositive = {
                cancelBenchmark()
                updateStringSetting(TEST_HOST, requireNotNull(DnsNames.host(it)))
                results = emptyList()
                refreshSettings()
            })
    }

    private fun startBenchmark() {
        if (benchmarkJob != null) return
        val generation = ++benchmarkGeneration
        val config = NetworkDnsStore.load(requireContext())
        val host = stringSetting(TEST_HOST, "example.com")
        results = emptyList()
        benchmarkJob = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (provider in DnsBenchmark.providers(config)) {
                    progress = "正在查询 ${provider?.name ?: "系统 DNS"} · $host"
                    refreshSettings()
                    val result = runInterruptible(Dispatchers.IO) { DnsBenchmark.measure(config, provider, host) }
                    if (generation == benchmarkGeneration) results = results + result
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == benchmarkGeneration) lastError = "测速未完成，请检查测试域名和网络后重试。"
            } finally {
                if (generation == benchmarkGeneration) {
                    benchmarkJob = null
                    progress = ""
                    if (view != null) refreshSettings()
                }
            }
        }
        benchmarkJob?.start()
        refreshSettings()
    }

    private fun showResult(result: DnsProbeResult) {
        val details = "测试域名：${result.hostname}\n" + result.samples.mapIndexed { index, sample ->
            "第 ${index + 1} 次：${sample.milliseconds?.let { "${ms(it)}，${sample.addressCount} 个地址" } ?: sample.error}"
        }.joinToString("\n")
        val provider = result.provider
        showComposeActionListDialog(result.name, if (provider == null) listOf("关闭") else listOf("选为 DoH 服务", "关闭"),
            message = details, onSelected = { index ->
                if (index == 0 && provider != null) {
                    val config = NetworkDnsStore.load(requireContext())
                    save(config.copy(endpoint = provider.url, bootstrap = provider.bootstrap))
                }
            })
    }

    private fun showHelp() {
        showComposeConfirmDialog("覆盖范围与测速说明", message =
            "书源指定 IP、Hosts 优先于域名规则，域名规则优先于功能选择。\n\n" +
            "分流只控制应用实际发起的原生请求。登录页、WebView 子资源、模板的外部脚本请求、系统或第三方 TTS 不会自动跟随。代理通常负责目标域名解析；应用可能只解析代理服务器。\n\n" +
            "测速使用当前保存的服务与 IPv6 选项，直接查询域名，不使用 Hosts、业务分流或系统回退。DoH 不使用本地 HTTP 缓存，首次包含建连，复测可复用连接；系统 DNS 的缓存由系统控制。结果是 DNS 查询耗时，不代表下载速度。\n\n" +
            "网络设置、Hosts、Cronet 保存后重启生效；测速结果不会自动更换服务商。",
            showNegative = false, onPositive = {})
    }

    private fun ms(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "失败"
    companion object { private const val TEST_HOST = "networkDnsTestHost" }
}
