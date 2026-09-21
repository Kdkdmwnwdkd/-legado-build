package io.legado.app.ui.widget.number

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.dpToPx
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager
import kotlin.math.roundToInt

class NumberPickerDialog(private val context: Context, private val isDecimalMode: Boolean = false) {

    private var title: String = ""
    private var maxValue: Int? = null
    private var minValue: Int? = null
    private var value: Int? = null
    private var customButtonTextId: Int? = null
    private var customButtonListener: (() -> Unit)? = null

    fun setTitle(title: String): NumberPickerDialog {
        this.title = title
        return this
    }

    fun setMaxValue(value: Int): NumberPickerDialog {
        maxValue = value
        return this
    }

    fun setMinValue(value: Int): NumberPickerDialog {
        minValue = value
        return this
    }

    fun setValue(value: Int): NumberPickerDialog {
        this.value = value
        return this
    }

    fun setCustomButton(textId: Int, listener: (() -> Unit)?): NumberPickerDialog {
        customButtonTextId = textId
        customButtonListener = listener
        return this
    }

    fun show(callBack: ((value: Int) -> Unit)?) {
        val dialog = ComponentDialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    NumberPickerContent(
                        title = title,
                        minValue = minValue ?: 0,
                        maxValue = maxValue ?: 999,
                        initialValue = value ?: minValue ?: 0,
                        isDecimalMode = isDecimalMode,
                        customButtonText = customButtonTextId?.let(context::getString),
                        onCustomClick = {
                            customButtonListener?.invoke()
                            dialog.dismiss()
                        },
                        onCancel = { dialog.dismiss() },
                        onConfirm = {
                            callBack?.invoke(it)
                            dialog.dismiss()
                        }
                    )
                }
            }
        )
        dialog.setOnShowListener {
            val width = minOf(
                (context.windowManager.windowSize.widthPixels * 0.92f).toInt(),
                420.dpToPx()
            )
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }
}

@Composable
private fun NumberPickerContent(
    title: String,
    minValue: Int,
    maxValue: Int,
    initialValue: Int,
    isDecimalMode: Boolean,
    customButtonText: String?,
    onCustomClick: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val safeMin = minOf(minValue, maxValue)
    val safeMax = maxOf(minValue, maxValue)
    var currentValue by remember {
        mutableIntStateOf(initialValue.coerceIn(safeMin, safeMax))
    }
    var inputText by remember(currentValue, isDecimalMode) {
        mutableStateOf(formatPickerValue(currentValue, isDecimalMode))
    }
    val usePercentSlider = !isDecimalMode &&
        safeMax == 100 &&
        safeMin in 0..100 &&
        safeMin < safeMax
    fun inputValueOrCurrent(): Int {
        return parsePickerValue(inputText, isDecimalMode)
            ?.coerceIn(safeMin, safeMax)
            ?: currentValue
    }
    fun committedValue(): Int {
        val committed = inputValueOrCurrent()
        currentValue = committed
        inputText = formatPickerValue(committed, isDecimalMode)
        return committed
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = style.titleFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (usePercentSlider) {
                LegadoMiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = style.fieldSurface,
                    contentColor = style.primaryText,
                    cornerRadius = style.actionRadius,
                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "${currentValue.coerceIn(safeMin, safeMax)}%",
                        color = style.primaryText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppThemedStepperSlider(
                        value = currentValue.coerceIn(safeMin, safeMax),
                        range = safeMin..safeMax,
                        onValueChange = {
                            currentValue = it.coerceIn(safeMin, safeMax)
                            inputText = formatPickerValue(currentValue, isDecimalMode)
                        },
                        palette = palette
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StepButton(
                        text = "-",
                        enabled = inputValueOrCurrent() > safeMin,
                        palette = palette,
                        onClick = {
                            currentValue = (inputValueOrCurrent() - 1).coerceAtLeast(safeMin)
                            inputText = formatPickerValue(currentValue, isDecimalMode)
                        }
                    )
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                safeMin < 0 -> KeyboardType.Text
                                isDecimalMode -> KeyboardType.Decimal
                                else -> KeyboardType.Number
                            }
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            color = style.primaryText,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = style.primaryText,
                            unfocusedTextColor = style.primaryText,
                            focusedContainerColor = style.fieldSurface,
                            unfocusedContainerColor = style.fieldSurface,
                            focusedBorderColor = style.accent.copy(alpha = 0.55f),
                            unfocusedBorderColor = style.stroke,
                            cursorColor = style.accent
                        )
                    )
                    StepButton(
                        text = "+",
                        enabled = inputValueOrCurrent() < safeMax,
                        palette = palette,
                        onClick = {
                            currentValue = (inputValueOrCurrent() + 1).coerceAtMost(safeMax)
                            inputText = formatPickerValue(currentValue, isDecimalMode)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (usePercentSlider) {
                    "$safeMin% - $safeMax%"
                } else {
                    "${formatPickerValue(safeMin, isDecimalMode)} - ${formatPickerValue(safeMax, isDecimalMode)}"
                },
                color = style.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                customButtonText?.let {
                    LegadoMiuixActionButton(
                        text = it,
                        palette = palette,
                        onClick = onCustomClick,
                        modifier = Modifier.weight(1f),
                        cornerRadius = style.actionRadius,
                        minWidth = 0.dp,
                        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    )
                }
                LegadoMiuixActionButton(
                    text = stringResource(android.R.string.cancel),
                    palette = palette,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    cornerRadius = style.actionRadius,
                    minWidth = 0.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                )
                LegadoMiuixActionButton(
                    text = stringResource(android.R.string.ok),
                    palette = palette,
                    onClick = {
                        onConfirm(committedValue())
                    },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    cornerRadius = style.actionRadius,
                    minWidth = 0.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun StepButton(
    text: String,
    enabled: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit
) {
    val actionRadius = palette.actionRadius ?: LocalContext.current.composeActionRadius()
    LegadoMiuixCard(
        modifier = Modifier
            .width(48.dp)
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) palette.surfaceVariant else palette.surfaceVariant.copy(alpha = 0.42f),
        contentColor = if (enabled) palette.primaryText else palette.secondaryText.copy(alpha = 0.52f),
        cornerRadius = actionRadius,
        insidePadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) palette.primaryText else palette.secondaryText.copy(alpha = 0.52f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatPickerValue(value: Int, decimalMode: Boolean): String {
    return if (decimalMode) {
        (value / 10.0).toString()
    } else {
        value.toString()
    }
}

private fun parsePickerValue(value: String, decimalMode: Boolean): Int? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (decimalMode) {
        normalized.toDoubleOrNull()
            ?.takeIf { java.lang.Double.isFinite(it) }
            ?.let { (it * 10).roundToInt() }
    } else {
        normalized.toIntOrNull()
    }
}
