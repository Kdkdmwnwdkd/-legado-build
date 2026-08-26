package io.legado.app.ui.video.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionRow
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.number.NumberPickerDialog

class SettingsDialog(private val context: Context, private val callBack: CallBack? = null) :
    ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    VideoSettingsContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun VideoSettingsContent(style: AppDialogStyle) {
        var autoPlay by rememberSaveable { mutableStateOf(VideoPlay.autoPlay) }
        var startFull by rememberSaveable { mutableStateOf(VideoPlay.startFull) }
        var showStartFull by rememberSaveable { mutableStateOf(true) }
        var fullBottomProgress by rememberSaveable { mutableStateOf(VideoPlay.fullBottomProgressBar) }
        var longPressSpeed by rememberSaveable { mutableIntStateOf(VideoPlay.longPressSpeed) }

        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.config_settings),
                    color = style.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AppDialogSwitchRow(
                    text = stringResource(R.string.auto_play),
                    checked = autoPlay,
                    onCheckedChange = { checked ->
                        autoPlay = checked
                        showStartFull = checked
                        VideoPlay.autoPlay = checked
                    }
                )
                if (showStartFull) {
                    AppDialogSwitchRow(
                        text = stringResource(R.string.start_full),
                        checked = startFull,
                        onCheckedChange = { checked ->
                            startFull = checked
                            VideoPlay.startFull = checked
                        }
                    )
                }
                AppDialogSwitchRow(
                    text = stringResource(R.string.full_bottom_progress),
                    checked = fullBottomProgress,
                    onCheckedChange = { checked ->
                        fullBottomProgress = checked
                        VideoPlay.fullBottomProgressBar = checked
                    }
                )
                LegadoMiuixActionRow(
                    text = longPressSpeed.toPressSpeedStr(),
                    palette = style.toMiuixPalette(),
                    onClick = {
                        showPressSpeedDialog { value ->
                            longPressSpeed = value
                        }
                    },
                    cornerRadius = style.actionRadius
                )
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showPressSpeedDialog(onChanged: (Int) -> Unit) {
        NumberPickerDialog(requireContext(), true)
            .setTitle(getString(R.string.press_speed))
            .setMaxValue(60)
            .setMinValue(5)
            .setValue(VideoPlay.longPressSpeed)
            .setCustomButton((R.string.btn_default_s)) {
                VideoPlay.longPressSpeed = 30
                onChanged(30)
            }
            .show {
                VideoPlay.longPressSpeed = it
                onChanged(it)
            }
    }

    private fun Int.toPressSpeedStr(): String {
        return context.getString(R.string.press_speed_summary, this / 10.0f)
    }

    interface CallBack {
//        fun upUi()
    }
}
