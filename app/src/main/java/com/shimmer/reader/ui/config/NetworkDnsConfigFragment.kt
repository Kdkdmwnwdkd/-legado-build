package com.shimmer.reader.ui.config

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.constant.PreferKey
import com.shimmer.reader.help.http.dns.DnsBenchmark
import com.shimmer.reader.help.http.dns.DnsDomainRule
import com.shimmer.reader.help.http.dns.DnsHosts
import com.shimmer.reader.help.http.dns.DnsNames
import com.shimmer.reader.help.http.dns.DnsProbeResult
import com.shimmer.reader.help.http.dns.DnsScope
import com.shimmer.reader.help.http.dns.DohProvider
import com.shimmer.reader.help.http.dns.NetworkDnsConfig
import com.shimmer.reader.help.http.dns.NetworkDnsStore
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingItemSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.config.compose.SettingSwitchSpec
import com.shimmer.reader.ui.widget.compose.showComposeActionListDialog
import com.shimmer.reader.ui.widget.compose.showComposeChoiceListDialog
import com.shimmer.reader.ui.widget.compose.showComposeConfirmDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextFormDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextInputDialog
import com.shimmer.reader.utils.restart
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
        if (NetworkDnsStore.hasInvalidSettings(requireContext())) lastError = getString(R.string.dns_invalid_saved_settings)
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
                SettingSwitchSpec(
                    PreferKey.networkDnsConfig,
                    getString(R.string.dns_enable_title),
                    config.enabled,
                    { save(config.copy(enabled = it)) },
                    getString(R.string.dns_enable_summary)
                ),
                action(
                    "dns_provider",
                    getString(R.string.dns_provider_title),
                    selected?.name ?: getString(R.string.dns_custom_service),
                    ::chooseProvider
                ),
                action(
                    "dns_custom",
                    getString(R.string.dns_endpoint_title),
                    selected?.url ?: getString(R.string.dns_custom_saved),
                    ::editEndpoint
                ),
                action(
                    "dns_restart",
                    getString(R.string.dns_restart_title),
                    lastError ?: getString(R.string.dns_restart_hint)
                ) {
                    showComposeConfirmDialog(
                        getString(R.string.dns_restart_confirm_title),
                        getString(R.string.dns_restart_confirm_message),
                        onPositive = ::restartWithSavedSettings
                    )
                }
            )),
            SettingSectionSpec(title = getString(R.string.dns_by_feature), items = DnsScope.entries.map { scope ->
                SettingSwitchSpec(
                    "dns_scope_${scope.key}",
                    getString(scope.titleRes),
                    scope in config.scopes,
                    { checked ->
                        save(config.copy(scopes = if (checked) config.scopes + scope else config.scopes - scope))
                    },
                    getString(if (scope in config.scopes) R.string.dns_scope_on_summary else R.string.dns_scope_off_summary)
                )
            }),
            SettingSectionSpec(title = getString(R.string.dns_domain_exceptions), items = listOf(
                action(
                    "dns_rule_add",
                    getString(R.string.dns_add_rule),
                    getString(R.string.dns_add_rule_summary)
                ) { editRule(null) }
            ) + config.rules.map { rule ->
                action(
                    "dns_rule_${rule.pattern}",
                    rule.pattern,
                    getString(if (rule.doh) R.string.dns_use_doh else R.string.dns_use_system)
                ) { editRule(rule) }
            }),
            SettingSectionSpec(title = getString(R.string.dns_benchmark_section), items = buildList {
                add(
                    action(
                        "dns_test_host",
                        getString(R.string.dns_test_host_title),
                        stringSetting(TEST_HOST, "example.com")
                    ) { editTestHost() }
                )
                add(
                    action(
                        "dns_benchmark",
                        getString(if (running) R.string.dns_benchmark_stop else R.string.dns_benchmark_start),
                        if (running) progress else getString(R.string.dns_benchmark_summary)
                    ) {
                        if (running) {
                            cancelBenchmark()
                            refreshSettings()
                        } else {
                            startBenchmark()
                        }
                    }
                )
                results.forEach { result ->
                    add(
                        action(
                            "dns_result_${result.provider?.id ?: "system"}",
                            result.name,
                            getString(
                                R.string.dns_result_summary,
                                result.successCount,
                                result.samples.size,
                                ms(result.firstMilliseconds),
                                ms(result.medianMilliseconds)
                            )
                        ) { showResult(result) }
                    )
                }
            }),
            SettingSectionSpec(title = getString(R.string.dns_advanced), items = listOf(
                SettingSwitchSpec(
                    "dns_ipv6",
                    getString(R.string.dns_ipv6_title),
                    config.includeIpv6,
                    { save(config.copy(includeIpv6 = it)) },
                    getString(R.string.dns_ipv6_summary)
                ),
                SettingSwitchSpec(
                    "dns_local",
                    getString(R.string.dns_local_title),
                    config.localNamesUseSystem,
                    { save(config.copy(localNamesUseSystem = it)) },
                    getString(R.string.dns_local_summary)
                ),
                SettingSwitchSpec(
                    "dns_fallback",
                    getString(R.string.dns_fallback_title),
                    config.fallbackToSystem,
                    { save(config.copy(fallbackToSystem = it)) },
                    getString(R.string.dns_fallback_summary)
                ),
                action(
                    PreferKey.customHosts,
                    getString(R.string.dns_custom_hosts),
                    getString(R.string.dns_custom_hosts_summary),
                    ::editHosts
                ),
                SettingSwitchSpec(
                    PreferKey.cronet,
                    getString(R.string.dns_cronet_title),
                    booleanSetting(PreferKey.cronet, false),
                    { updateBooleanSetting(PreferKey.cronet, it) },
                    getString(R.string.dns_cronet_summary)
                ),
                action(
                    "dns_help",
                    getString(R.string.dns_help_title),
                    getString(R.string.dns_help_summary),
                    ::showHelp
                )
            ))
        )
        return SettingPageSpec(titleRes, sections)
    }

    private fun action(key: String, title: String, summary: String, click: () -> Unit): SettingItemSpec =
        SettingActionSpec(key = key, title = title, summary = summary, onClick = click)

    private fun save(config: NetworkDnsConfig) {
        runCatching { NetworkDnsStore.save(requireContext(), config) }
            .onSuccess { lastError = null }
            .onFailure { lastError = it.message ?: getString(R.string.dns_config_invalid) }
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
                    lastError = getString(R.string.dns_save_failed_retry)
                    refreshSettings()
                }
            } finally { applyingSettings = false }
        }
    }

    private fun chooseProvider() {
        val config = NetworkDnsStore.load(requireContext())
        showComposeChoiceListDialog(
            getString(R.string.dns_provider_title),
            DohProvider.presets.map { it.name } + getString(R.string.dns_custom_service),
            selectedIndex = DohProvider.presets.indexOfFirst { it.url == config.endpoint && it.bootstrap == config.bootstrap }
                .let { if (it < 0) DohProvider.presets.size else it },
            onSelected = { index ->
                DohProvider.presets.getOrNull(index)?.let { save(config.copy(endpoint = it.url, bootstrap = it.bootstrap)) } ?: editEndpoint()
            })
    }

    private fun editEndpoint() {
        val config = NetworkDnsStore.load(requireContext())
        fun candidate(values: List<String>): NetworkDnsConfig = config.copy(
            endpoint = values[0].trim(),
            bootstrap = values[1].split(Regex("[,，\\s]+")).filter { it.isNotBlank() }
        )
        showComposeTextFormDialog(
            getString(R.string.dns_provider_title),
            labels = listOf(
                getString(R.string.dns_endpoint_label_url),
                getString(R.string.dns_endpoint_label_bootstrap)
            ),
            initialValues = listOf(config.endpoint, config.bootstrap.joinToString(", ")),
            message = getString(R.string.dns_endpoint_message),
            validateInput = { values -> runCatching { candidate(values).validated() }.isSuccess },
            onPositive = { save(candidate(it)) })
    }

    private fun editRule(original: DnsDomainRule?) {
        showComposeTextInputDialog(
            getString(R.string.dns_rule_title),
            hint = "example.com / *.example.com",
            initialValue = original?.pattern.orEmpty(),
            message = getString(R.string.dns_rule_message),
            validateInput = { runCatching { DnsDomainRule(it, false).normalized() }.isSuccess },
            neutralText = if (original == null) null else getString(R.string.dns_rule_delete),
            onNeutral = if (original == null) null else { {
                val config = NetworkDnsStore.load(requireContext())
                save(config.copy(rules = config.rules.filterNot { it.pattern == original.pattern }))
            } },
            onPositive = { pattern ->
                showComposeChoiceListDialog(
                    getString(R.string.dns_rule_use_title, pattern.trim()),
                    listOf("DoH", getString(R.string.dns_use_system)),
                    selectedIndex = if (original?.doh != false) 0 else 1,
                    onSelected = { index ->
                        val rule = DnsDomainRule(pattern, index == 0).normalized()
                        val config = NetworkDnsStore.load(requireContext())
                        save(config.copy(rules = config.rules.filterNot { it.pattern == original?.pattern || it.pattern == rule.pattern } + rule))
                    })
            })
    }

    private fun editHosts() {
        showComposeTextInputDialog(
            getString(R.string.dns_custom_hosts),
            initialValue = stringSetting(PreferKey.customHosts, ""),
            hint = "{\"example.com\": [\"192.0.2.1\"]}",
            minLines = 4,
            maxLines = 12,
            message = getString(R.string.dns_hosts_message),
            validateInput = { runCatching { DnsHosts.parse(it, strict = true) }.isSuccess },
            onPositive = { updateStringSetting(PreferKey.customHosts, it.trim()) })
    }

    private fun editTestHost() {
        showComposeTextInputDialog(
            getString(R.string.dns_test_host_title),
            initialValue = stringSetting(TEST_HOST, "example.com"),
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
                    progress = getString(
                        R.string.dns_probe_progress,
                        provider?.name ?: getString(R.string.system_dns),
                        host
                    )
                    refreshSettings()
                    val result = runInterruptible(Dispatchers.IO) { DnsBenchmark.measure(config, provider, host) }
                    if (generation == benchmarkGeneration) results = results + result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == benchmarkGeneration) lastError = getString(R.string.dns_benchmark_failed)
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
        val details = getString(R.string.dns_result_message_title, result.hostname) + "\n" +
            result.samples.mapIndexed { index, sample ->
                getString(
                    R.string.dns_result_sample,
                    index + 1,
                    sample.milliseconds?.let {
                        getString(R.string.dns_result_sample_ok, ms(it), sample.addressCount)
                    } ?: sample.error.orEmpty()
                )
            }.joinToString("\n")
        val provider = result.provider
        showComposeActionListDialog(
            result.name,
            if (provider == null) {
                listOf(getString(R.string.dns_close))
            } else {
                listOf(getString(R.string.dns_result_choose), getString(R.string.dns_close))
            },
            message = details,
            onSelected = { index ->
                if (index == 0 && provider != null) {
                    val config = NetworkDnsStore.load(requireContext())
                    save(config.copy(endpoint = provider.url, bootstrap = provider.bootstrap))
                }
            })
    }

    private fun showHelp() {
        showComposeConfirmDialog(
            getString(R.string.dns_help_title),
            message = getString(R.string.dns_help_body),
            showNegative = false, onPositive = {})
    }

    private fun ms(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: getString(R.string.dns_probe_failed)

    companion object { private const val TEST_HOST = "networkDnsTestHost" }
}
