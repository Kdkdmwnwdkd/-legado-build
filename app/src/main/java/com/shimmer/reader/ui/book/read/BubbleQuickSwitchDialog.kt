package com.shimmer.reader.ui.book.read

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shimmer.reader.R
import splitties.init.appCtx
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.config.BubblePackageManager
import com.shimmer.reader.model.ImageProvider
import com.shimmer.reader.ui.widget.compose.AppDialogFrame
import com.shimmer.reader.ui.widget.compose.AppManagementCard
import com.shimmer.reader.ui.widget.compose.AppManagementListRow
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppManagementPalette
import com.shimmer.reader.utils.SvgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class BubbleQuickSwitchDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onSelected: ((BubblePackageManager.Entry) -> Unit)? = null
    private var onManage: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val context = LocalContext.current
                val palette = rememberAppManagementPalette()
                var entries by remember { mutableStateOf<List<BubblePackageManager.Entry>>(emptyList()) }
                var forceSoftwareBubble by remember {
                    mutableStateOf(AppConfig.forceSoftwareParagraphBubble)
                }
                val previews = remember { mutableStateMapOf<String, Bitmap>() }
                val activeDirName = BubblePackageManager.activeDirName()
                LaunchedEffect(Unit) {
                    val loaded = BubblePackageManager.loadEntries()
                    entries = loaded
                    loaded.forEach { entry ->
                        withContext(Dispatchers.Default) {
                            bubblePreviewBitmap(context.resources.displayMetrics.density, entry.config)
                        }?.let { bitmap ->
                            previews[entry.dirName] = bitmap
                        }
                    }
                }
                AppDialogFrame(
                    title = stringResource(R.string.bubble_quick_pick_title),
                    scrollContent = false,
                    content = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item(key = "forceSoftwareBubble") {
                                AppManagementListRow(
                                    title = stringResource(R.string.bubble_force_software_title),
                                    subtitle = stringResource(R.string.bubble_force_software_summary),
                                    palette = palette,
                                    switchChecked = forceSoftwareBubble,
                                    onSwitchChange = { checked ->
                                        forceSoftwareBubble = checked
                                        AppConfig.forceSoftwareParagraphBubble = checked
                                        ImageProvider.clear()
                                        (activity as? ReadBookActivity)?.refreshParagraphRuleLayout()
                                    },
                                    onClick = {
                                        val checked = !forceSoftwareBubble
                                        forceSoftwareBubble = checked
                                        AppConfig.forceSoftwareParagraphBubble = checked
                                        ImageProvider.clear()
                                        (activity as? ReadBookActivity)?.refreshParagraphRuleLayout()
                                    }
                                )
                            }
                            items(entries, key = { it.dirName }) { entry ->
                                val active = entry.dirName == activeDirName
                                AppManagementCard(
                                    palette = palette,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dismissAllowingStateLoss()
                                            onSelected?.invoke(entry)
                                        },
                                    insidePadding = PaddingValues(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BubblePreview(bitmap = previews[entry.dirName])
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.config.name,
                                                color = if (active) palette.settings.accent else palette.settings.primaryText,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (active) stringResource(R.string.bubble_applied) else bubbleSourceText(entry.source),
                                                color = palette.settings.secondaryText,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        val dialogPalette = rememberAppManagementPalette().miuix
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.bubble_manage),
                            palette = dialogPalette,
                            onClick = {
                                dismissAllowingStateLoss()
                                onManage?.invoke()
                            }
                        )
                        LegadoMiuixActionButton(
                            text = getString(R.string.cancel),
                            palette = dialogPalette,
                            onClick = { dismissAllowingStateLoss() }
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            onSelected: (BubblePackageManager.Entry) -> Unit,
            onManage: () -> Unit
        ): BubbleQuickSwitchDialog {
            return BubbleQuickSwitchDialog().apply {
                this.onSelected = onSelected
                this.onManage = onManage
            }
        }
    }
}

@Composable
private fun BubblePreview(bitmap: Bitmap?) {
    val palette = rememberAppManagementPalette()
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.miuix.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(text = stringResource(R.string.bubble), color = palette.settings.secondaryText, fontSize = 12.sp)
        }
    }
}

private fun bubblePreviewBitmap(density: Float, config: BubblePackageManager.Config): Bitmap? {
    return runCatching {
        val color = when {
            AppConfig.isNightTheme -> config.nightNormalColor
            else -> config.dayNormalColor
        }?.takeIf { it.isNotBlank() } ?: BubblePackageManager.DEFAULT_NORMAL_COLOR
        val svg = config.svgTemplate
            .replace("\${color}", color)
            .replace("\${num}", "12")
        val sidePx = (58 * density).toInt()
        SvgUtils.createBitmap(ByteArrayInputStream(svg.toByteArray()), sidePx, sidePx)
    }.getOrNull()
}

private fun bubbleSourceText(source: BubblePackageManager.Source): String {
    return when (source) {
        BubblePackageManager.Source.BUILTIN -> appCtx.getString(R.string.bubble_source_builtin)
        BubblePackageManager.Source.LOCAL -> appCtx.getString(R.string.bubble_source_local)
        BubblePackageManager.Source.REMOTE -> appCtx.getString(R.string.bubble_source_remote)
        BubblePackageManager.Source.BOTH -> appCtx.getString(R.string.bubble_source_both)
    }
}
