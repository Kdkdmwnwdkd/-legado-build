package com.shimmer.reader.ui.config.compose

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.shimmer.reader.R
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.ui.widget.compose.showComposeChoiceListDialog
import com.shimmer.reader.utils.defaultSharedPreferences
import com.shimmer.reader.utils.getPrefBoolean
import com.shimmer.reader.utils.getPrefInt
import com.shimmer.reader.utils.getPrefString
import com.shimmer.reader.utils.putPrefBoolean
import com.shimmer.reader.utils.putPrefInt

abstract class ComposeSettingFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @get:StringRes
    protected abstract val titleRes: Int

    protected open val applyActivityTitle: Boolean = true

    protected open val autoOpenTargetItem: Boolean = true

    protected open val drawPanelImage: Boolean = true

    private val refreshTick = mutableIntStateOf(0)
    private val scrollTargetKey = mutableStateOf<String?>(null)
    private var targetKeyHandled = false

    /**
     * 设置项搜索需要在不打开页面的情况下拿到各页条目，此时 Fragment 没有宿主。
     * 构建索引期间用它顶替 [requireContext]，索引结束立即置空，避免持有 Activity。
     *
     * 注意：Fragment.getResources()/getString() 是 final 的，覆盖不了，
     * 所以设置页统一用 [str] 与 [strings] 取资源，才能在无宿主时构建页面描述。
     */
    private var indexContext: Context? = null

    /** 设置项读写用的上下文。正常运行时就是所在 Activity，构建搜索索引时是 Application。 */
    protected val settingContext: Context
        get() = indexContext ?: requireContext()

    protected val strings: Resources
        get() = settingContext.resources

    protected fun str(@StringRes resId: Int): String = settingContext.getString(resId)

    protected fun str(@StringRes resId: Int, vararg formatArgs: Any): String =
        settingContext.getString(resId, *formatArgs)

    protected val prefs: SharedPreferences
        get() = settingContext.defaultSharedPreferences

    // 子类里还有 getPrefXxx(key) 的写法（Fragment 扩展函数会走 requireContext），
    // 这里用同签名的成员函数遮蔽扩展，索引期间才不会因为拿不到 Activity 而崩。
    fun getPrefString(key: String, defValue: String? = null): String? =
        settingContext.getPrefString(key, defValue)

    fun getPrefBoolean(key: String, defValue: Boolean = false): Boolean =
        settingContext.getPrefBoolean(key, defValue)

    fun getPrefInt(key: String, defValue: Int = 0): Int =
        settingContext.getPrefInt(key, defValue)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                refreshTick.intValue
                LegadoComposeTheme {
                    SettingSpecScreen(
                        page = buildPageSpec(),
                        scrollTargetKey = scrollTargetKey.value,
                        drawPanelImage = drawPanelImage,
                        onTargetReady = ::handleTargetReady,
                        onTargetMissing = ::consumeMissingTarget,
                        onItemClick = ::handleItemClick
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        if (applyActivityTitle) {
            activity?.setTitle(titleRes)
        }
        consumeTargetKey()
        refreshSettings()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshSettings()
        if (key != null) {
            onSettingPreferenceChanged(key)
        }
    }

    protected abstract fun buildPageSpec(): SettingPageSpec

    protected open fun normalizeTargetKey(rawKey: String): String = rawKey

    protected open fun onSettingPreferenceChanged(key: String) = Unit

    protected fun refreshSettings() {
        refreshTick.intValue += 1
    }

    protected fun booleanSetting(
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return settingContext.getPrefBoolean(key, defaultValue)
    }

    protected fun stringSetting(
        key: String,
        defaultValue: String
    ): String {
        return settingContext.getPrefString(key, defaultValue) ?: defaultValue
    }

    protected fun intSetting(
        key: String,
        defaultValue: Int
    ): Int {
        return settingContext.getPrefInt(key, defaultValue)
    }

    protected fun updateBooleanSetting(
        key: String,
        value: Boolean
    ) {
        settingContext.putPrefBoolean(key, value)
    }

    protected fun updateStringSetting(
        key: String,
        value: String
    ) {
        settingContext.defaultSharedPreferences.edit { putString(key, value) }
    }

    protected fun updateIntSetting(
        key: String,
        value: Int
    ) {
        settingContext.putPrefInt(key, value)
    }

    /**
     * 不打开页面直接描述本页条目，供设置项搜索建立索引。
     * 走的是和界面同一份 [buildPageSpec]，页面改了什么，搜索就能搜到什么。
     */
    internal fun describeForSearch(context: Context, pageConfigTag: String): List<SettingSearchEntry> {
        indexContext = context.applicationContext
        return try {
            val page = buildPageSpec()
            val appContext = context.applicationContext
            val pageTitle = runCatching { appContext.getString(page.titleRes) }.getOrDefault("")
            page.sections.flatMap { section ->
                section.items.filter { it.visible }.map { item ->
                    SettingSearchEntry(
                        pageConfigTag = pageConfigTag,
                        pageTitle = pageTitle,
                        sectionTitle = section.title?.toString().orEmpty(),
                        key = item.key,
                        title = item.title.toString(),
                        summary = item.summary?.toString().orEmpty(),
                        keywords = item.searchKeys
                    )
                }
            }
        } finally {
            indexContext = null
        }
    }

    private fun consumeTargetKey() {
        if (targetKeyHandled) return
        val rawTargetKey = activity?.intent?.getStringExtra("targetKey")?.trim().orEmpty()
        if (rawTargetKey.isBlank()) return
        scrollTargetKey.value = normalizeTargetKey(rawTargetKey)
    }

    private fun handleTargetReady(targetKey: String) {
        if (targetKeyHandled) return
        val item = findItem(targetKey) ?: return consumeMissingTarget()
        targetKeyHandled = true
        scrollTargetKey.value = null
        view?.post {
            if (autoOpenTargetItem) {
                handleItemClick(item)
            }
            activity?.intent?.removeExtra("targetKey")
        }
    }

    private fun consumeMissingTarget() {
        if (targetKeyHandled) return
        targetKeyHandled = true
        scrollTargetKey.value = null
        activity?.intent?.removeExtra("targetKey")
    }

    private fun findItem(targetKey: String): SettingItemSpec? {
        return buildPageSpec().sections
            .asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.visible && (it.key == targetKey || targetKey in it.searchKeys) }
    }

    private fun handleItemClick(item: SettingItemSpec) {
        if (!item.enabled) return
        when (item) {
            is SettingActionSpec -> item.onClick()
            is SettingSwitchSpec -> item.onCheckedChange(!item.checked)
            is SettingChoiceSpec -> showChoiceDialog(item)
            is SettingSliderSpec -> Unit
        }
    }

    private fun showChoiceDialog(item: SettingChoiceSpec) {
        showComposeChoiceListDialog(
            title = item.title,
            labels = item.options.map { it.label },
            selectedIndex = item.options.indexOfFirst { it.value == item.selectedValue },
            descriptions = item.options.map { it.description?.toString().orEmpty() },
            iconNames = item.options.map { it.iconName.orEmpty() },
            negativeText = getString(R.string.cancel),
            onSelected = { index ->
                item.options.getOrNull(index)?.value?.let(item.onSelected)
            }
        )
    }
}
