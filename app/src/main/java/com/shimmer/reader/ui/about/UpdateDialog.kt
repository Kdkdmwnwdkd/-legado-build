package com.shimmer.reader.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.shimmer.reader.R
import com.shimmer.reader.help.update.AppUpdate
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.model.Download
import com.shimmer.reader.ui.widget.compose.AppDialogFrame
import com.shimmer.reader.ui.widget.compose.ComposeDialogFragment
import com.shimmer.reader.ui.widget.compose.AppDialogSize
import com.shimmer.reader.ui.widget.compose.LegadoMiuixActionButton
import com.shimmer.reader.ui.widget.compose.rememberAppDialogStyle
import com.shimmer.reader.ui.widget.compose.toMiuixPalette
import com.shimmer.reader.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin

class UpdateDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putStringArrayList("headerNames", ArrayList(updateInfo.requestHeaders.keys))
            putStringArrayList("headerValues", ArrayList(updateInfo.requestHeaders.values))
        }
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
                val title = arguments?.getString("newVersion").orEmpty()
                val updateBody = arguments?.getString("updateBody")
                val url = arguments?.getString("url")
                val name = arguments?.getString("name")
                val headerNames = arguments?.getStringArrayList("headerNames").orEmpty()
                val headerValues = arguments?.getStringArrayList("headerValues").orEmpty()

                if (updateBody == null) {
                    toastOnUi(R.string.toast_no_data)
                    dismissAllowingStateLoss()
                    return@setContent
                }

                val context = LocalContext.current
                val markwon = Markwon.builder(context)
                    .usePlugin(GlideImagesPlugin.create(context))
                    .usePlugin(HtmlPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .build()

                AppDialogFrame(
                    title = title,
                    content = {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth(),
                            factory = { ctx ->
                                TextView(ctx).apply {
                                    setTextColor(style.primaryText.toArgb())
                                    typeface = ctx.uiTypeface()
                                    setTextIsSelectable(true)
                                    val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                                    setPadding(pad, pad, pad, pad)
                                }
                            },
                            update = { textView ->
                                markwon.setMarkdown(textView, updateBody)
                            }
                        )
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.action_download),
                            palette = palette,
                            onClick = {
                                if (url != null && name != null) {
                                    val headers = headerNames.mapIndexedNotNull { index, headerName ->
                                        val headerValue = headerValues.getOrNull(index)
                                        if (headerName.isBlank() || headerValue.isNullOrBlank()) {
                                            null
                                        } else {
                                            headerName to headerValue
                                        }
                                    }.toMap()
                                    Download.start(requireContext(), url, name, headers)
                                    toastOnUi(R.string.download_start)
                                }
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

}
