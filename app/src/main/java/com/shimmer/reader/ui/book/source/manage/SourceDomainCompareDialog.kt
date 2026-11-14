package com.shimmer.reader.ui.book.source.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.shimmer.reader.help.source.SourceCapability
import com.shimmer.reader.help.source.SourceDomainGroup
import com.shimmer.reader.help.source.SourceDomainIndex
import com.shimmer.reader.help.source.toCapability
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.AppDialogStyle
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.utils.NetworkUtils
import kotlinx.coroutines.flow.conflate

/**
 * 同一个域名下的书源横向对比。
 *
 * 只读静态规则配置，不联网、不改任何数据；
 * 叠在一起的能力只是提示「这个源有的别人也有」，删不删由用户决定。
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
        Spacer(Modifier.height(10.dp))
        Text(
            text = "搜 搜索　发 发现　详 详情　目 目录　正 正文；暗色表示没有配这条规则",
            color = style.secondaryText,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        sources.forEach { source ->
            CapabilityRow(source = source, style = style)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "仅按规则是否配置判断，不代表站点当前可用；要测实际响应请用「书源校验」",
            color = style.secondaryText,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CapabilityRow(source: SourceCapability, style: AppDialogStyle) {
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
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    color = style.secondaryText,
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
            text = if (source.hasRespondTime) "${source.respondTime / 1000}s" else "—",
            color = style.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
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
