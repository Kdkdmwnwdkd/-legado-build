package io.legado.app.lib.prefs

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.progressAdd

class SeekBarPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {
    var minValue = 0
        private set
    var maxValue = 1000
        private set

    private var mSeekBar: SeekBar? = null
    private var mValueText: TextView? = null

    private var seekPlus: ImageView? = null
    private var seekReduce: ImageView? = null

    var value: Int = 0
        set(value) {
            field = value.coerceIn(minValue, maxValue)
            persistInt(field)
            mSeekBar?.progress = field
            mValueText?.text = field.toString()
        }

    init {
        layoutResource = R.layout.view_preference_seekbar
        val a = context.obtainStyledAttributes(attrs, R.styleable.NumberPickerPreference)
        minValue = a.getInt(R.styleable.NumberPickerPreference_MinValue, minValue)
        maxValue = a.getInt(R.styleable.NumberPickerPreference_MaxValue, maxValue)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        mSeekBar = holder.findViewById(R.id.seek_bar) as? SeekBar
        mValueText = holder.findViewById(R.id.tv_seek_value) as? TextView
        seekPlus = holder.findViewById(R.id.iv_seek_plus) as? ImageView
        seekReduce = holder.findViewById(R.id.iv_seek_reduce) as? ImageView
        val titleView = holder.findViewById(R.id.preference_title) as? TextView
        val summaryView = holder.findViewById(R.id.preference_desc) as? TextView
        titleView?.text = title
        summaryView?.text = summary
        summaryView?.isGone = summary.isNullOrEmpty()
        if (!holder.itemView.isInEditMode) {
            val primaryText = context.primaryTextColor
            val secondaryText = context.secondaryTextColor
            titleView?.applyUiTitleTypeface(context)
            titleView?.setTextColor(context.titleTextColor)
            summaryView?.typeface = context.uiTypeface()
            summaryView?.setTextColor(secondaryText)
            mValueText?.typeface = context.uiTypeface()
            mValueText?.setTextColor(primaryText)
            seekPlus?.setColorFilter(primaryText)
            seekReduce?.setColorFilter(primaryText)
        }
        mSeekBar?.apply {
            max = maxValue - minValue
            progress = value - minValue

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val newValue = progress + minValue
                    mValueText?.text = newValue.toString()
                    if (fromUser) {
                        value = newValue
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val newValue = seekBar.progress + minValue
                    if (callChangeListener(newValue)) {
                        value = newValue
                    }
                }
            })
        }
        mValueText?.text = value.toString()
        seekPlus?.setOnClickListener {
            mSeekBar?.progressAdd(1)
            value++
        }
        seekReduce?.setOnClickListener {
            mSeekBar?.progressAdd(-1)
            value--
        }
        PreferenceItemStyle.apply(this, holder)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 500)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedInt((defaultValue as? Int) ?: 0)
    }


}
