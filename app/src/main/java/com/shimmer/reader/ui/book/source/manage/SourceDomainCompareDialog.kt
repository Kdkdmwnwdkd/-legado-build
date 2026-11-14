package com.shimmer.reader.ui.book.source.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.data.appDb
import com.shimmer.reader.help.source.DomainPickPlan
import com.shimmer.reader.help.source.ProbeResult
import com.shimmer.reader.help.source.SourceCapability
import com.shimmer.reader.help.source.SourceDomainGroup
import com.shimmer.reader.help.source.SourceDomainIndex
import com.shimmer.reader.help.source.SourceDomainPick
import com.shimmer.reader.help.source.SourceProbe
import com.shimmer.reader.help.source.SourceProbeCache
import com.shimmer.reader.help.source.probeText
import com.shimmer.reader.help.source.toCapability
import com.shimmer.reader.model.CheckSource
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.AppDialogStyle
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 同一个域名下的书源横向对比。
 *
 * 上半部分是静态信息：谁有搜索、谁有发现、谁的正文规则不全，全部只读书源配置。
 * 下半部分可以点「实测这一组」：只测域名和搜索两段，几十秒内看出这组里现在谁还能用。
 * 实测不写注释、不改分组、不自动停用，唯一的副作用是把搜索耗时写回 respondTime。
 */
class SourceDomainCompareDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val hostArg: String by lazy {
        requireArguments().getString(ARG_HOST).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val rows by appDb.bookSourceDao.flowCapabilities()
                    .conflate()
                    .collectAsState(initial = emptyList())
                val group = remember(rows, hostArg) {
                    val caps = rows.asSequence()
                        .filter { hostOfSource(it.bookSourceUrl) == hostArg }
                        .map { it.toCapability() }
                        .toList()
                    if (caps.isEmpty()) {
                        null
                    } else {
                        SourceDomainIndex.group(
                            sources = caps,
                            hostOf = NetworkUtils::getSubDomainOrNull,
                            onlyDuplicated = false
                        ).firstOrNull()
                    }
                }
                SourceDomainCompareContent(
                    host = hostArg,
                    group = group,
                    style = style
                )
            }
        }
    }

    companion object {
        private const val ARG_HOST = "host"

        fun newInstance(host: String): SourceDomainCompareDialog {
            return SourceDomainCompareDialog().apply {
                arguments = Bundle().apply { putString(ARG_HOST, host) }
            }
        }
    }
}

/**
 * 取域名，和 BookSourceActivity.getSourceHost 保持同一口径：
 * 取不到时统一回落到 "#"，保证列表标题和弹窗里的分组永远对得上。
 */
private fun hostOfSource(url: String): String {
    return NetworkUtils.getSubDomainOrNull(url)?.takeIf { it.isNotBlank() } ?: "#"
}

@Composable
private fun SourceDomainCompareContent(
    host: String,
    group: SourceDomainGroup?,
    style: AppDialogStyle,
) {
    val sources = group?.sources.orEmpty()
    // 已经停用的源不再参与实测，省得在死源上浪费时间
    val probeTargets = sources.filter { it.enabled }
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, ProbeResult>() }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    var lastDisabled by remember { mutableStateOf<List<String>>(emptyList()) }

    // 报表刷新（比如写回了 respondTime）会重建 group，这里把已有结论接回来
    LaunchedEffect(group) {
        group?.sources?.forEach { source ->
            SourceProbeCache.get(source.url)?.let { results[source.url] = it }
        }
    }

    // 一组最多十几个源，直接算比缓存更省心
    val probeMap = results.toMap()
    val plan = group?.let { SourceDomainPick.plan(it, probeMap) } ?: DomainPickPlan()

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = host,
            color = style.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = group?.summary() ?: "这个域名下已经没有书源了",
            color = style.secondaryText,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProbeButton(
                running = running,
                done = doneCount,
                total = probeTargets.size,
                style = style,
                onClick = {
                    if (!running && probeTargets.isNotEmpty()) {
                        val targets = probeTargets.map { it.url }
                        scope.launch {
                            running = true
                            doneCount = 0
                            SourceProbeCache.clear(targets)
                            targets.forEach { results.remove(it) }
                            val keyword = CheckSource.keyword
                            // 同域名一次只打两个，别为了测速把站点惹毛
                            targets.chunked(2).forEach { chunk ->
                                chunk.map { url -> async { SourceProbe.probe(url, keyword) } }
                                    .awaitAll()
                                    .forEach { result ->
                                        results[result.url] = result
                                        SourceProbeCache.put(result)
                                    }
                                doneCount = (doneCount + chunk.size).coerceAtMost(targets.size)
                            }
                            running = false
                        }
                    }
                }
            )
            if (plan.hasAction) {
                Spacer(Modifier.width(8.dp))
                PickButton(
                    count = plan.disable.size,
                    style = style,
                    enabled = !running,
                    onClick = { asking = true }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "搜 搜索　发 发现　详 详情　目 目录　正 正文；暗色表示没有配这条规则",
            color = style.secondaryText,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        sources.forEach { source ->
            CapabilityRow(
                source = source,
                probe = results[source.url],
                reason = plan.reasons[source.url],
                style = style
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (results.isEmpty()) {
                "仅按规则是否配置判断，不代表站点当前可用；点上面的按钮可以只测域名和搜索两段"
            } else {
                "实测只覆盖域名与搜索两段，不检查目录和正文；结论仅保留 5 分钟，不代表书源整体质量"
            },
            color = style.secondaryText,
            fontSize = 11.sp
        )
        if (lastDisabled.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已停用 ${lastDisabled.size} 个源",
                    color = style.secondaryText,
                    fontSize = 12.sp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "撤销",
                    color = style.accent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val urls = lastDisabled
                            lastDisabled = emptyList()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val list = urls.mapNotNull {
                                        appDb.bookSourceDao.getBookSource(it)
                                    }
                                    list.forEach { it.enabled = true }
                                    if (list.isNotEmpty()) {
                                        appDb.bookSourceDao.update(*list.toTypedArray())
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (asking) {
        val targets = plan.disable
        AlertDialog(
            onDismissRequest = { asking = false },
            shape = RoundedCornerShape(style.panelRadius),
            containerColor = style.surface,
            title = {
                Text(
                    text = "停用 ${targets.size} 个冗余源",
                    color = style.primaryText,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "这些源的能力被同组里更靠谱的源覆盖，停用后不影响搜索和阅读：",
                        color = style.secondaryText,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            append(targets.take(8).joinToString("\n") { "· ${it.name}" })
                            if (targets.size > 8) append("\n… 等 ${targets.size} 个")
                        },
                        color = style.primaryText,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "只停用，不删除。随时可以在书源列表里重新启用。",
                        color = style.secondaryText,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    val urls = targets.map { it.url }
                    if (urls.isNotEmpty()) {
                        lastDisabled = urls
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val list = urls.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
                                list.forEach { it.enabled = false }
                                if (list.isNotEmpty()) {
                                    appDb.bookSourceDao.update(*list.toTypedArray())
                                }
                            }
                        }
                    }
                }) {
                    Text("停用", color = style.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) {
                    Text("取消", color = style.secondaryText)
                }
            }
        )
    }
}

@Composable
private fun ProbeButton(
    running: Boolean,
    done: Int,
    total: Int,
    style: AppDialogStyle,
    onClick: () -> Unit,
) {
    val enabled = !running && total > 0
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (enabled) style.accent.copy(alpha = 0.14f)
                else style.secondaryText.copy(alpha = 0.08f)
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (running) "实测中 $done/$total" else "实测这一组",
            color = if (enabled || running) style.accent else style.secondaryText.copy(alpha = 0.5f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PickButton(
    count: Int,
    style: AppDialogStyle,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (enabled) style.danger.copy(alpha = 0.12f)
                else style.secondaryText.copy(alpha = 0.08f)
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "停用冗余 $count 个",
            color = if (enabled) style.danger else style.secondaryText.copy(alpha = 0.5f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun CapabilityRow(
    source: SourceCapability,
    probe: ProbeResult?,
    reason: String?,
    style: AppDialogStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                color = if (source.enabled) style.primaryText else style.secondaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                source.group?.takeIf { it.isNotBlank() }?.let { append(it) }
                if (!source.enabled) {
                    if (isNotEmpty()) append(" · ")
                    append("已停用")
                }
                probe?.takeIf { !it.passed && it.reason.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it.reason)
                }
                reason?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("冗余，$it")
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    color = when {
                        probe != null && !probe.passed -> style.danger
                        reason != null -> style.danger.copy(alpha = 0.8f)
                        else -> style.secondaryText
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            CapabilityBadge("搜", source.hasSearch, style)
            CapabilityBadge("发", source.hasExplore, style)
            CapabilityBadge("详", source.hasInfo, style)
            CapabilityBadge("目", source.hasToc, style)
            CapabilityBadge("正", source.hasContent, style)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = probeText(source, probe),
            color = when {
                probe == null -> style.secondaryText
                probe.passed -> style.accent
                else -> style.danger
            },
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(52.dp)
        )
    }
}

@Composable
private fun CapabilityBadge(label: String, on: Boolean, style: AppDialogStyle) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (on) style.accent.copy(alpha = 0.16f) else style.secondaryText.copy(alpha = 0.08f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (on) style.accent else style.secondaryText.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
    }
}
