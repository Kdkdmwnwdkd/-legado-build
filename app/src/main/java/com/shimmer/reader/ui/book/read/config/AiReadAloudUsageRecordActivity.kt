package com.shimmer.reader.ui.book.read.config

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseActivity
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.AiReadAloudUsageRecord
import com.shimmer.reader.databinding.ActivityThemeManageBinding
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.lib.theme.themeMutedColorOrDefault
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.ui.widget.compose.ComposeActionListDialog
import com.shimmer.reader.ui.widget.compose.ComposeConfirmDialog
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import splitties.init.appCtx

class AiReadAloudUsageRecordActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = UsageAdapter()
    private val selectedIds = linkedSetOf<Long>()
    private var records: List<AiReadAloudUsageRecord> = emptyList()
    private var typeFilter: String = ""
    private var currentBookOnly = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        load()
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.ai_usage_title)
        tabBar.visibility = View.GONE
        btnAdd.text = getString(R.string.ai_usage_select_all)
        btnAdd.background = UiCorner.actionSelector(
            themeCardColorOrDefault(),
            themeMutedColorOrDefault(),
            UiCorner.actionRadius(this@AiReadAloudUsageRecordActivity)
        )
        btnAdd.setOnClickListener { toggleSelectAll() }
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@AiReadAloudUsageRecordActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        root.applyUiBodyTypefaceDeep(uiTypeface())
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_FILTER_TYPE, 0, getString(R.string.ai_usage_menu_filter_type)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_FILTER_BOOK, 1, getString(R.string.ai_usage_menu_filter_book)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_DELETE_SELECTED, 2, getString(R.string.ai_usage_menu_delete_selected)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_CLEAR_ALL, 3, getString(R.string.ai_usage_menu_clear_all)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_FILTER_TYPE -> showTypeFilter()
            MENU_FILTER_BOOK -> {
                currentBookOnly = !currentBookOnly
                selectedIds.clear()
                load()
            }
            MENU_DELETE_SELECTED -> deleteSelected()
            MENU_CLEAR_ALL -> confirmClearAll()
        }
        return true
    }

    private fun load() {
        lifecycleScope.launch {
            val bookUrl = if (currentBookOnly) ReadBook.book?.bookUrl.orEmpty() else ""
            val loaded = withContext(Dispatchers.IO) {
                appDb.aiReadAloudUsageRecordDao.list(typeFilter, bookUrl)
            }
            records = loaded
            selectedIds.retainAll(records.mapTo(hashSetOf()) { it.id })
            adapter.submit(records)
            updateSummary()
        }
    }

    private fun updateSummary() {
        val totalInput = records.sumOf { it.inputTokens }
        val totalCached = records.sumOf { it.cachedInputTokens }
        val totalOutput = records.sumOf { it.outputTokens }
        val total = records.sumOf { it.totalTokens.takeIf { value -> value > 0 } ?: (it.inputTokens + it.outputTokens) }
        val typeText = typeLabel(typeFilter).takeIf { typeFilter.isNotBlank() } ?: getString(R.string.ai_usage_all_types)
        val bookText = if (currentBookOnly) {
            getString(R.string.ai_usage_scope_current_book)
        } else {
            getString(R.string.ai_usage_scope_all_books)
        }
        binding.tvSummary.text = getString(
            R.string.ai_usage_summary,
            typeText,
            bookText,
            records.size,
            totalInput,
            totalCached,
            totalOutput,
            total,
            selectedIds.size
        )
    }

    private fun showTypeFilter() {
        val types = listOf(
            "" to getString(R.string.ai_usage_type_all),
            AiReadAloudUsageRecord.TYPE_ROLE to getString(R.string.ai_usage_type_role),
            AiReadAloudUsageRecord.TYPE_BGM to getString(R.string.bgm_label_music),
            AiReadAloudUsageRecord.TYPE_SFX to getString(R.string.bgm_label_sfx),
            AiReadAloudUsageRecord.TYPE_AUDIO to getString(R.string.ai_usage_type_audio)
        )
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.ai_usage_menu_filter_type),
                labels = types.map { if (it.first == typeFilter) "${it.second} ✓" else it.second },
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    types.getOrNull(index)?.let { selected ->
                        typeFilter = selected.first
                        selectedIds.clear()
                        load()
                    }
                }
            )
        )
    }

    private fun toggleSelectAll() {
        if (records.isEmpty()) return
        if (selectedIds.size == records.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            records.mapTo(selectedIds) { it.id }
        }
        adapter.submit(records)
        updateSummary()
    }

    private fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) {
            toastOnUi(R.string.toast_long_press_record_first)
            return
        }
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.ai_usage_delete_title),
                message = getString(R.string.ai_usage_delete_message, ids.size),
                positiveText = getString(R.string.delete),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.aiReadAloudUsageRecordDao.deleteByIds(ids)
                        selectedIds.clear()
                        launch(Dispatchers.Main) { load() }
                    }
                }
            )
        )
    }

    private fun confirmClearAll() {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.ai_usage_clear_title),
                message = getString(R.string.ai_usage_clear_message),
                positiveText = getString(R.string.clear),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.aiReadAloudUsageRecordDao.clear()
                        selectedIds.clear()
                        launch(Dispatchers.Main) { load() }
                    }
                }
            )
        )
    }

    private fun showRecordActions(record: AiReadAloudUsageRecord) {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = typeLabel(record.type),
                labels = listOf(getString(R.string.delete)),
                dangerIndices = setOf(0),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    if (index == 0) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            appDb.aiReadAloudUsageRecordDao.delete(record.id)
                            selectedIds.remove(record.id)
                            launch(Dispatchers.Main) { load() }
                        }
                    }
                }
            )
        )
    }

    private inner class UsageAdapter : RecyclerView.Adapter<UsageHolder>() {
        private var items: List<AiReadAloudUsageRecord> = emptyList()

        fun submit(value: List<AiReadAloudUsageRecord>) {
            items = value
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsageHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                background = UiCorner.actionSelector(
                    parent.context.themeCardColorOrDefault(),
                    parent.context.themeMutedColorOrDefault(),
                    UiCorner.panelRadius(parent.context)
                )
            }
            val title = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(primaryTextColor)
                typeface = uiTypeface()
            }
            val sub = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(secondaryTextColor)
                typeface = uiTypeface()
                gravity = Gravity.START
            }
            root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 4.dpToPx()
            })
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 10.dpToPx()
            }
            return UsageHolder(root, title, sub)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: UsageHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class UsageHolder(
        itemView: View,
        private val title: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        fun bind(record: AiReadAloudUsageRecord) {
            val selected = record.id in selectedIds
            title.text = buildString {
                if (selected) append("✓ ")
                append(typeLabel(record.type))
                append(" · ")
                append(statusLabel(record.status))
                if (record.batchName.isNotBlank()) append(" · ").append(record.batchName)
            }
            sub.text = buildList {
                add(
                    record.chapterTitle.ifBlank {
                        record.bookName.ifBlank { getString(R.string.ai_usage_unknown_chapter) }
                    }
                )
                add(formatTime(record.createdAt))
                if (record.modelId.isNotBlank()) add(record.modelId)
                add(getString(R.string.ai_usage_tokens_input, record.inputTokens))
                if (record.cachedInputTokens > 0) {
                    add(getString(R.string.ai_usage_tokens_cached, record.cachedInputTokens))
                }
                add(getString(R.string.ai_usage_tokens_output, record.outputTokens))
                add(
                    getString(
                        R.string.ai_usage_tokens_total,
                        record.totalTokens.takeIf { it > 0 }
                            ?: (record.inputTokens + record.outputTokens)
                    )
                )
                if (record.error.isNotBlank()) add(record.error)
            }.joinToString(" · ")
            itemView.setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    toggle(record.id)
                } else {
                    showRecordActions(record)
                }
            }
            itemView.setOnLongClickListener {
                toggle(record.id)
                true
            }
        }

        private fun toggle(id: Long) {
            if (!selectedIds.add(id)) selectedIds.remove(id)
            adapter.submit(records)
            updateSummary()
        }
    }

    companion object {
        private const val MENU_FILTER_TYPE = 1
        private const val MENU_FILTER_BOOK = 2
        private const val MENU_DELETE_SELECTED = 3
        private const val MENU_CLEAR_ALL = 4

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        private fun typeLabel(type: String): String = when (type) {
            AiReadAloudUsageRecord.TYPE_ROLE -> appCtx.getString(R.string.ai_usage_type_role)
            AiReadAloudUsageRecord.TYPE_BGM -> appCtx.getString(R.string.bgm_label_music)
            AiReadAloudUsageRecord.TYPE_SFX -> appCtx.getString(R.string.bgm_label_sfx)
            AiReadAloudUsageRecord.TYPE_AUDIO -> appCtx.getString(R.string.ai_usage_type_audio)
            else -> appCtx.getString(R.string.ai_usage_type_all)
        }

        private fun statusLabel(status: String): String = when (status) {
            AiReadAloudUsageRecord.STATUS_SUCCESS -> appCtx.getString(R.string.ai_usage_status_success)
            AiReadAloudUsageRecord.STATUS_FAILED -> appCtx.getString(R.string.ai_usage_status_failed)
            AiReadAloudUsageRecord.STATUS_CACHE -> appCtx.getString(R.string.ai_usage_status_cache)
            else -> status.ifBlank { appCtx.getString(R.string.ai_usage_status_unknown) }
        }

        private fun formatTime(time: Long): String {
            return if (time > 0) timeFormat.format(Date(time)) else "-"
        }
    }
}
