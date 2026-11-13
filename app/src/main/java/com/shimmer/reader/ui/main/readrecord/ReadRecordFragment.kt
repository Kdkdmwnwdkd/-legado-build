package com.shimmer.reader.ui.main.readrecord

import android.app.DatePickerDialog
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseFragment
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.databinding.ActivityReadRecordBinding
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.reader.ReadRecordExporter
import com.shimmer.reader.help.config.TopBarConfig
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.dialogs.selector
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.ui.about.ReadHeatmapCell
import com.shimmer.reader.ui.about.ReadRecordCoverRow
import com.shimmer.reader.ui.about.ReadRecordCoverUi
import com.shimmer.reader.ui.about.ReadRecordComponentConfigDialog
import com.shimmer.reader.ui.about.ReadRecordComponentType
import com.shimmer.reader.ui.about.ReadRecordComponents
import com.shimmer.reader.ui.about.ReadRecordGoalConfig
import com.shimmer.reader.ui.about.ReadRecordGoalCardContent
import com.shimmer.reader.ui.about.ReadRecordGoalUi
import com.shimmer.reader.ui.about.ReadRecordRankDialog
import com.shimmer.reader.ui.about.ReadRecordRankItem
import com.shimmer.reader.ui.about.ReadRecordWidgetStore
import com.shimmer.reader.ui.about.ReadRecentVisualItem
import com.shimmer.reader.ui.about.ReadRecordDailyList
import com.shimmer.reader.ui.about.ReadRecordDayUi
import com.shimmer.reader.ui.about.ReadRecordOverviewCard
import com.shimmer.reader.ui.about.ReadRecordOverviewUi
import com.shimmer.reader.ui.about.ReadRecordRankList
import com.shimmer.reader.ui.about.ReadRecordRankUi
import com.shimmer.reader.ui.about.ReadRecordRecentBookUi
import com.shimmer.reader.ui.about.ReadRecordRecentBooksList
import com.shimmer.reader.ui.about.openReadRecordBook
import com.shimmer.reader.ui.about.showReadRecordBookActionDialog
import com.shimmer.reader.ui.about.showReadRecordGoalDialog
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.image.ImageCropContract
import com.shimmer.reader.ui.main.MainFragmentInterface
import com.shimmer.reader.ui.widget.MainTopBarView
import com.shimmer.reader.ui.widget.RoundedTagBarView
import com.shimmer.reader.ui.widget.compose.ComposeTextInputDialog
import com.shimmer.reader.ui.widget.compose.LegadoComposeTheme
import com.shimmer.reader.utils.ColorUtils
import com.shimmer.reader.utils.ImageCropHelper
import com.shimmer.reader.utils.applyMainBottomBarPadding
import com.shimmer.reader.utils.applyStatusBarPadding
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.sendToClip
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.observeEvent
import com.shimmer.reader.utils.registerForActivityResult
import com.shimmer.reader.utils.startActivityForBook
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class ReadRecordFragment() : BaseFragment(R.layout.activity_read_record), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(ActivityReadRecordBinding::bind)
    private val headlineFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_date_pattern), Locale.getDefault())
    }
    private val fullDayFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_date_pattern), Locale.getDefault())
    }
    private val monthFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_month_pattern), Locale.getDefault())
    }
    private val lastOpenFormatter by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    private var currentHeatmapCells: List<ReadHeatmapCell> = emptyList()
    private var selectedDate: LocalDate = LocalDate.now()
    private var loadJob: Job? = null
    private var loadedDate: LocalDate? = null
    private var lastLoadTime = 0L
    private var lastRecentReadTime = 0L
    private var componentItems = ReadRecordComponents.load()
    private var currentRankItems: List<ReadRecordRankItem> = emptyList()
    private var currentGoalConfig: ReadRecordGoalConfig = ReadRecordWidgetStore.loadGoalConfig()
    private var currentTodayTime: Long = 0L
    private var currentTotalTime: Long = 0L
    private var currentReadBookCount: Int = 0
    private var currentDashboard: ReadRecordDashboard? = null
    private val overviewUiState = mutableStateOf<ReadRecordOverviewUi?>(null)
    private val recentBooksUiState = mutableStateOf<List<ReadRecordRecentBookUi>>(emptyList())
    private val dailyRecordsUiState = mutableStateOf<List<ReadRecordDayUi>>(emptyList())
    private val rankUiState = mutableStateOf<List<ReadRecordRankUi>>(emptyList())
    private val recentCoversUiState = mutableStateOf<List<ReadRecordCoverUi>>(emptyList())
    private val goalUiState = mutableStateOf<ReadRecordGoalUi?>(null)
    private var currentRecentBooks: List<RecentReadBook> = emptyList()
    private var currentDailyTimeline: List<DailyReadSummary> = emptyList()
    private var currentVisibleRankItems: List<ReadRecordRankItem> = emptyList()
    private var currentRecentCovers: List<ReadRecentVisualItem> = emptyList()
    private var recordDaysExpanded = false
    private var pendingAvatarUpdate: ((String) -> Unit)? = null
    private var pendingAvatarCropRequest: ImageCropHelper.Request? = null
    private val selectGoalAvatar = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            startAvatarCrop(uri)
        } ?: run {
            pendingAvatarUpdate = null
        }
    }
    private val cropGoalAvatar = registerForActivityResult(ImageCropContract()) { result ->
        val request = pendingAvatarCropRequest ?: return@registerForActivityResult
        pendingAvatarCropRequest = null
        if (result == null) {
            pendingAvatarUpdate = null
            return@registerForActivityResult
        }
        if (java.io.File(result.path).exists()) {
            pendingAvatarUpdate?.invoke(result.path)
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
        pendingAvatarUpdate = null
    }
    private val exportRecordResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            showDialogFragment(
                ComposeTextInputDialog.create(
                    title = getString(R.string.export_success),
                    hint = getString(R.string.path),
                    initialValue = uri.toString(),
                    readOnly = true,
                    positiveText = getString(android.R.string.ok),
                    negativeText = getString(android.R.string.cancel),
                    onPositive = { requireContext().sendToClip(it) }
                )
            )
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.visibility = View.GONE
        binding.scrollView.applyMainBottomBarPadding(withInitialPadding = true)
        binding.llRecordHeader.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.setMode(MainTopBarView.Mode.READ_RECORD)
        binding.topBar.setSearchEntryVisible(false)
        binding.topBar.setOnFilterExpandedChangedListener {
            recordDaysExpanded = it
        }
        binding.topBar.titleSelect.setOnClickListener {
            showYearSelector()
        }
        binding.topBar.moreButton.setOnClickListener {
            showComponentConfigDialog()
        }
        binding.topBar.primaryBar.setOnTagClickListener { index ->
            selectMonth(index + 1)
        }
        binding.topBar.tagsBar.setOnTagClickListener { index ->
            val month = YearMonth.from(selectedDate)
            val date = month.atDay((index + 1).coerceIn(1, month.lengthOfMonth()))
            if (selectedDate != date) {
                selectedDate = date
                recordDaysExpanded = false
                loadData(force = true)
            }
        }
        binding.tvRecordDate.setOnClickListener {
            if (isRegularReadRecordTopBar()) {
                showYearSelector()
            } else {
                showDatePicker()
            }
        }
        binding.ivComponentMenu.setOnClickListener {
            showComponentConfigDialog()
        }
        binding.panelOverview.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.panelOverview.setContent {
            LegadoComposeTheme {
                overviewUiState.value?.let { ui ->
                    ReadRecordOverviewCard(ui = ui)
                }
            }
        }
        binding.llRecentBooks.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.llRecentBooks.setContent {
            LegadoComposeTheme {
                ReadRecordRecentBooksList(
                    items = recentBooksUiState.value,
                    onClick = { index -> openRecentBook(index) },
                    onLongClick = { index -> showRecentBookActions(index) }
                )
            }
        }
        binding.llDailyRecords.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.llDailyRecords.setContent {
            LegadoComposeTheme {
                ReadRecordDailyList(
                    items = dailyRecordsUiState.value,
                    onLongClick = { index -> confirmDeleteDailyRecord(index) }
                )
            }
        }
        binding.llReadRank.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.llReadRank.setContent {
            LegadoComposeTheme {
                ReadRecordRankList(
                    items = rankUiState.value,
                    onClick = { index -> openRankBook(index) },
                    onLongClick = { index -> showRankBookActions(index) }
                )
            }
        }
        binding.ivRankMore.setOnClickListener {
            ReadRecordRankDialog.show(requireContext(), currentRankItems, ::formatDuring) { item ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDao.deleteByName(item.displayName)
                    }
                    loadData(force = true)
                }
            }
        }
        binding.ivGoalEdit.setOnClickListener {
            requireContext().showReadRecordGoalDialog(
                initial = currentGoalConfig,
                onPickAvatarRequest = { update ->
                    pendingAvatarUpdate = update
                    selectGoalAvatar.launch {
                        mode = HandleFileContract.IMAGE
                        title = getString(R.string.read_record_goal_avatar)
                    }
                }
            ) { config ->
                currentGoalConfig = config
                ReadRecordWidgetStore.saveGoalConfig(config)
                renderGoalCard(currentTodayTime, currentTotalTime, currentReadBookCount)
            }
        }
        binding.rvRecentCovers.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.rvRecentCovers.setContent {
            LegadoComposeTheme {
                ReadRecordCoverRow(
                    items = recentCoversUiState.value,
                    onClick = { index -> openRecentCover(index) },
                    onLongClick = { index -> showRecentCoverActions(index) }
                )
            }
        }
        binding.goalCardContent.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.goalCardContent.setContent {
            LegadoComposeTheme {
                goalUiState.value?.let { ui ->
                    ReadRecordGoalCardContent(ui = ui)
                }
            }
        }
        applyComponentLayout()
        preloadData()
    }

    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.TOP_BAR_CHANGED) {
            if (it == AppConfig.isNightTheme) {
                binding.topBar.refreshStyle()
                currentDashboard?.let(::renderDateTopBar)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            val latestRecentReadTime = withContext(IO) {
                appDb.readRecentBookDao.latestReadTime() ?: 0L
            }
            loadData(
                force = loadedDate == null ||
                    loadedDate != selectedDate ||
                    isDataStale() ||
                    latestRecentReadTime != lastRecentReadTime
            )
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.book_read_record, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
                supportToolbar?.menu?.findItem(R.id.menu_enable_record)?.isChecked =
                    AppConfig.enableReadRecord
            }

            R.id.menu_export_record -> exportReadRecord()

            R.id.menu_clear_record -> {
                alert(R.string.delete, R.string.sure_del) {
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.readRecordDao.clear()
                                appDb.readRecordDailyDao.clear()
                                appDb.readRecentBookDao.clear()
                                ReadRecordWidgetStore.clearRecentSnapshots()
                            }
                            loadData(force = true)
                        }
                    }
                    noButton()
                }
            }
        }
    }

    /** 导出已有记录，不触发重新统计 */
    private fun exportReadRecord() {
        val ctx = context ?: return
        ctx.selector(
            listOf<CharSequence>(
                getString(R.string.read_record_export_markdown),
                getString(R.string.read_record_export_csv)
            )
        ) { _, which ->
            val asCsv = which == 1
            lifecycleScope.launch {
                val bundle = withContext(IO) { loadExportBundle() }
                val text = if (asCsv) {
                    ReadRecordExporter.buildCsv(bundle.days)
                } else {
                    ReadRecordExporter.buildMarkdown(
                        summary = bundle.summary,
                        days = bundle.days,
                        books = bundle.books,
                        exportedAt = bundle.exportedAt
                    )
                }
                // 文件名带时间戳，连续导出两次不会在同一个目录里互相覆盖
                val stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                exportRecordResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "read-record-$stamp.${if (asCsv) "csv" else "md"}",
                        text,
                        if (asCsv) "text/csv" else "text/markdown"
                    )
                }
            }
        }
    }

    private fun loadExportBundle(): ExportBundle {
        val daily = appDb.readRecordDailyDao.allDesc
        val days = daily.map { ReadRecordExporter.Day(it.date, it.readTime) }
        val books = appDb.readRecordDao.allShow
            .map { ReadRecordExporter.Book(it.bookName, it.readTime) }
            .sortedByDescending { it.readTimeMs }
        val today = LocalDate.now().toString()
        return ExportBundle(
            summary = ReadRecordExporter.Summary(
                todayTimeMs = days.firstOrNull { it.date == today }?.readTimeMs ?: 0L,
                totalTimeMs = appDb.readRecordDao.allTime,
                activeDays = days.count { it.readTimeMs > 0L },
                bookCount = books.size
            ),
            days = days,
            books = books,
            exportedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        )
    }

    private fun preloadData() {
        loadData(force = true)
    }

    private fun isDataStale(): Boolean {
        return System.currentTimeMillis() - lastLoadTime > DATA_STALE_MS
    }

    private fun loadData(force: Boolean = false) {
        if (!force && loadedDate == selectedDate) return
        if (loadJob?.isActive == true) {
            if (!force) return
            loadJob?.cancel()
        }
        supportToolbar?.menu?.findItem(R.id.menu_enable_record)?.isChecked =
            AppConfig.enableReadRecord
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val loadDate = selectedDate
            val dashboard = withContext(IO) {
                buildDashboard(loadDate)
            }
            if (loadDate != selectedDate) return@launch
            loadedDate = loadDate
            lastLoadTime = System.currentTimeMillis()
            renderDashboard(dashboard)
        }
    }

    private fun startAvatarCrop(uri: Uri) {
        val request = ImageCropHelper.buildRequest(
            context = requireContext(),
            sourceUri = uri,
            requestCode = requestGoalAvatar,
            aspectWidth = 1,
            aspectHeight = 1,
            dirName = "readRecordGoalAvatar",
            prefix = "avatar",
            targetWidth = 512
        )
        pendingAvatarCropRequest = request
        cropGoalAvatar.launch(request.params)
    }

    private fun buildDashboard(today: LocalDate): ReadRecordDashboard {
        val month = YearMonth.from(today)
        val readRecordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
        val totalTime = appDb.readRecordDao.allTime
        val dailyStats = appDb.readRecordDailyDao.allDesc.mapNotNull { record ->
            runCatching {
                DailyReadSummary(
                    date = LocalDate.parse(record.date),
                    readTime = record.readTime
                )
            }.getOrNull()
        }.sortedByDescending { it.date }
        val dailyMap = dailyStats.associate { it.date to it.readTime }
        val recentBooks = appDb.readRecentBookDao.recentBooks(6)
            .map { book ->
                RecentReadBook(
                    book = book,
                    totalReadTime = readRecordMap[book.name]?.readTime ?: 0L
                )
            }
        val heatmapStart = today.minusDays(111)
        val heatmapCells = (0L..111L).map { offset ->
            val date = heatmapStart.plusDays(offset)
            ReadHeatmapCell(date, dailyMap[date] ?: 0L)
        }
        return ReadRecordDashboard(
            today = today,
            todayTime = dailyMap[today] ?: 0L,
            monthTime = dailyStats.filter { YearMonth.from(it.date) == month }.sumOf { it.readTime },
            totalTime = totalTime,
            activeDays = dailyStats.count { it.readTime > 0L },
            heatmapCells = heatmapCells,
            recentBooks = recentBooks,
            dailyTimeline = dailyStats.take(14),
            hasDailyStats = dailyStats.isNotEmpty(),
            recentCoverItems = ReadRecordWidgetStore.loadRecentVisualItems(5),
            rankItems = ReadRecordWidgetStore.buildRankItems(),
            goalConfig = ReadRecordWidgetStore.loadGoalConfig(),
            readBookCount = appDb.readRecordDao.allShow.size,
            latestRecentReadTime = appDb.readRecentBookDao.latestReadTime() ?: 0L
        )
    }

    private fun renderDashboard(dashboard: ReadRecordDashboard) {
        currentDashboard = dashboard
        currentHeatmapCells = dashboard.heatmapCells
        binding.tvRecordDate.text = dashboard.today.format(headlineFormatter)
        binding.tvRecordDateHint.text = getString(
            if (dashboard.hasDailyStats) {
                R.string.read_record_stats_ready
            } else {
                R.string.read_record_stats_waiting
            }
        )
        overviewUiState.value = dashboard.toOverviewUi()

        val startDate = dashboard.heatmapCells.firstOrNull()?.date ?: dashboard.today
        val centerDate = dashboard.heatmapCells.getOrNull(dashboard.heatmapCells.size / 2)?.date
            ?: dashboard.today
        val endDate = dashboard.heatmapCells.lastOrNull()?.date ?: dashboard.today
        binding.tvHeatmapMonthStart.text = startDate.format(monthFormatter)
        binding.tvHeatmapMonthCenter.text = centerDate.format(monthFormatter)
        binding.tvHeatmapMonthEnd.text = endDate.format(monthFormatter)
        binding.tvHeatmapEmpty.isVisible = !dashboard.hasDailyStats
        currentTodayTime = dashboard.todayTime
        currentTotalTime = dashboard.totalTime
        currentReadBookCount = dashboard.readBookCount
        lastRecentReadTime = dashboard.latestRecentReadTime

        renderDateTopBar(dashboard)
        renderRecentBooks(dashboard.recentBooks)
        renderDailyTimeline(dashboard.dailyTimeline, dashboard.hasDailyStats)
        renderRecentCovers(dashboard.recentCoverItems)
        renderReadRank(dashboard.rankItems.take(5), dashboard.rankItems)
        currentGoalConfig = dashboard.goalConfig
        renderGoalCard(dashboard.todayTime, dashboard.totalTime, dashboard.readBookCount)
        applyPageChrome()
    }

    private fun ReadRecordDashboard.toOverviewUi(): ReadRecordOverviewUi {
        val placeholder = getString(R.string.read_record_placeholder)
        return ReadRecordOverviewUi(
            todayValue = if (hasDailyStats) formatDuring(todayTime) else placeholder,
            todayLabel = if (today == LocalDate.now()) {
                getString(R.string.read_record_today_label)
            } else {
                getString(R.string.read_record_selected_day_label)
            },
            monthValue = if (hasDailyStats) formatDuring(monthTime) else placeholder,
            monthLabel = getString(R.string.read_record_month_label),
            totalValue = formatDuring(totalTime),
            totalLabel = getString(R.string.read_record_total_label),
            activeDaysValue = getString(R.string.read_record_active_days_value, activeDays),
            activeDaysLabel = getString(R.string.read_record_active_days_label)
        )
    }

    private fun renderDateTopBar(dashboard: ReadRecordDashboard) {
        val regular = isRegularReadRecordTopBar()
        binding.topBar.isVisible = regular
        binding.llRecordHeader.isVisible = !regular
        if (!regular) {
            binding.llRecordHeader.background = null
            binding.tvRecordDate.textSize = 28f
            binding.tvRecordDate.text = dashboard.today.format(headlineFormatter)
            binding.tvRecordDateHint.isVisible = true
            binding.tvRecordDateHint.text = getString(
                if (dashboard.hasDailyStats) {
                    R.string.read_record_stats_ready
                } else {
                    R.string.read_record_stats_waiting
                }
            )
            return
        }
        val month = YearMonth.from(dashboard.today)
        binding.topBar.setMode(MainTopBarView.Mode.READ_RECORD)
        binding.topBar.setSearchEntryVisible(false)
        binding.topBar.setTitle(getString(R.string.read_record_year_value, dashboard.today.year))
        binding.topBar.setPrimaryItems(
            (1..12).map { RoundedTagBarView.Item(getString(R.string.read_record_month_value, it)) },
            dashboard.today.monthValue - 1
        )
        binding.topBar.selectsBar.submitItems(emptyList(), -1)
        binding.topBar.showSelects(false)
        binding.topBar.tagsBar.submitItems(
            (1..month.lengthOfMonth()).map { RoundedTagBarView.Item(it.toString()) },
            dashboard.today.dayOfMonth - 1
        )
        binding.topBar.showTags(true)
        val defaultExpanded = TopBarConfig.currentConfig(requireContext(), AppConfig.isNightTheme).expandFiltersByDefault
        binding.topBar.setFiltersExpanded(defaultExpanded || recordDaysExpanded)
    }

    private fun selectMonth(monthValue: Int) {
        val targetMonth = YearMonth.of(selectedDate.year, monthValue)
        selectedDate = targetMonth.atDay(selectedDate.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
        recordDaysExpanded = false
        loadData(force = true)
    }

    private fun showYearSelector() {
        val years = ((selectedDate.year - 5)..(selectedDate.year + 1)).toList()
        requireContext().selector(
            getString(R.string.read_record_select_year),
            years.map { getString(R.string.read_record_year_value, it) }
        ) { _, index ->
            val targetYear = years[index]
            val targetMonth = YearMonth.of(targetYear, selectedDate.monthValue)
            selectedDate = targetMonth.atDay(selectedDate.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
            recordDaysExpanded = false
            loadData(force = true)
        }
    }

    private fun isRegularReadRecordTopBar(): Boolean {
        return TopBarConfig.currentConfig(requireContext(), AppConfig.isNightTheme).style == TopBarConfig.STYLE_REGULAR
    }

    private fun showComponentConfigDialog() {
        ReadRecordComponentConfigDialog.show(requireContext(), componentItems) { items ->
            componentItems = items.toMutableList()
            ReadRecordComponents.save(componentItems)
            applyComponentLayout()
            applyPageChrome()
        }
    }

    private fun applyComponentLayout() {
        if (componentItems.none { it.enabled }) {
            componentItems.firstOrNull()?.enabled = true
            ReadRecordComponents.save(componentItems)
        }
        val parent = binding.llReadRecordComponents
        val componentViews = linkedMapOf(
            ReadRecordComponentType.OVERVIEW to binding.panelOverview,
            ReadRecordComponentType.HEATMAP to binding.panelHeatmap,
            ReadRecordComponentType.RECENT_BOOKS to binding.panelRecentBooks,
            ReadRecordComponentType.DAILY_RECORDS to binding.panelDailyRecords,
            ReadRecordComponentType.RECENT_COVERS to binding.panelRecentCovers,
            ReadRecordComponentType.READ_RANK to binding.panelReadRank,
            ReadRecordComponentType.GOAL_CARD to binding.panelGoalCard
        )
        componentViews.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.isVisible = false
        }
        parent.removeAllViews()
        componentItems.filter { it.enabled }.forEachIndexed { index, item ->
            componentViews[item.type]?.let { view ->
                val lp = (view.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?: ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                lp.topMargin = if (index == 0) 0 else 16.dpToPx()
                view.layoutParams = lp
                view.isVisible = true
                parent.addView(view)
            }
        }
    }

    private fun showDatePicker() {
        val date = selectedDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                loadData(force = true)
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }

    private fun renderRecentBooks(items: List<RecentReadBook>) {
        currentRecentBooks = items
        recentBooksUiState.value = items.map {
            ReadRecordRecentBookUi(
                name = it.book.name,
                meta = buildRecentBookMeta(it.book),
                readTime = formatDuring(it.totalReadTime)
            )
        }
        binding.tvRecentBooksEmpty.isVisible = items.isEmpty()
    }

    private fun renderDailyTimeline(items: List<DailyReadSummary>, hasDailyStats: Boolean) {
        currentDailyTimeline = items
        dailyRecordsUiState.value = if (hasDailyStats) {
            items.map {
                ReadRecordDayUi(
                    title = it.date.format(fullDayFormatter),
                    subtitle = buildDaySubtitle(it.date),
                    readTime = formatDuring(it.readTime)
                )
            }
        } else {
            emptyList()
        }
        binding.tvDailyRecordsEmpty.isVisible = !hasDailyStats || items.isEmpty()
    }

    private fun openRecentBook(index: Int) {
        currentRecentBooks.getOrNull(index)?.let {
            startActivityForBook(it.book)
        }
    }

    private fun showRecentBookActions(index: Int) {
        val item = currentRecentBooks.getOrNull(index) ?: return
        requireContext().showReadRecordBookActionDialog(item.book.name, item.book, item.book.name) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(IO) {
                    appDb.readRecentBookDao.deleteSameBook(item.book.name, item.book.author)
                    ReadRecordWidgetStore.removeRecentSnapshot(item.book)
                }
                loadData(force = true)
            }
        }
    }

    private fun confirmDeleteDailyRecord(index: Int) {
        val item = currentDailyTimeline.getOrNull(index) ?: return
        alert(getString(R.string.delete), item.date.format(fullDayFormatter)) {
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDailyDao.delete(item.date.toString())
                    }
                    loadData(force = true)
                }
            }
            noButton()
        }
    }

    private fun renderRecentCovers(items: List<ReadRecentVisualItem>) {
        currentRecentCovers = items
        recentCoversUiState.value = items.map {
            ReadRecordCoverUi(book = it.book, snapshot = it.snapshot)
        }
        binding.tvRecentCoversEmpty.isVisible = items.isEmpty()
        binding.rvRecentCovers.isVisible = items.isNotEmpty()
    }

    private fun openRecentCover(index: Int) {
        currentRecentCovers.getOrNull(index)?.let {
            requireContext().openReadRecordBook(it.book, it.snapshot.name)
        }
    }

    private fun showRecentCoverActions(index: Int) {
        val item = currentRecentCovers.getOrNull(index) ?: return
        requireContext().showReadRecordBookActionDialog(
            title = item.book?.name ?: item.snapshot.name,
            book = item.book,
            fallbackName = item.snapshot.name
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(IO) {
                    item.book?.let { book ->
                        appDb.readRecentBookDao.deleteSameBook(book.name, book.author)
                        ReadRecordWidgetStore.removeRecentSnapshot(book)
                    } ?: run {
                        appDb.readRecentBookDao.delete(item.snapshot.bookUrl)
                        ReadRecordWidgetStore.removeRecentSnapshot(item.snapshot.bookUrl)
                    }
                }
                loadData(force = true)
            }
        }
    }

    private fun renderReadRank(items: List<ReadRecordRankItem>, allItems: List<ReadRecordRankItem>) {
        currentRankItems = allItems
        currentVisibleRankItems = items
        rankUiState.value = items.mapIndexed { index, item ->
            val name = item.book?.name ?: item.snapshot?.name ?: item.displayName
            val author = item.book?.author ?: item.snapshot?.author ?: item.displayAuthor
            ReadRecordRankUi(
                name = name,
                meta = if (author.isBlank()) {
                    getString(R.string.read_record_rank_number, index + 1)
                } else {
                    "${index + 1}. $author"
                },
                readTime = formatDuring(item.readTime),
                dimmed = item.book == null,
                book = item.book,
                snapshot = item.snapshot
            )
        }
        binding.tvReadRankEmpty.isVisible = items.isEmpty()
        binding.ivRankMore.isVisible = allItems.isNotEmpty()
    }

    private fun openRankBook(index: Int) {
        currentVisibleRankItems.getOrNull(index)?.let {
            requireContext().openReadRecordBook(it.book, it.displayName)
        }
    }

    private fun showRankBookActions(index: Int) {
        val item = currentVisibleRankItems.getOrNull(index) ?: return
        requireContext().showReadRecordBookActionDialog(
            title = item.book?.name ?: item.snapshot?.name ?: item.displayName,
            book = item.book,
            fallbackName = item.displayName
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(IO) {
                    appDb.readRecordDao.deleteByName(item.displayName)
                }
                loadData(force = true)
            }
        }
    }

    private fun renderGoalCard(todayTime: Long, totalTime: Long, readBookCount: Int) {
        val todayText = formatDuring(todayTime)
        val totalText = formatDuring(totalTime)
        val goalMs = currentGoalConfig.dailyGoalMinutes * 60L * 1000L
        val percent = if (goalMs <= 0L) 0 else ((todayTime * 100) / goalMs).toInt().coerceIn(0, 100)
        goalUiState.value = ReadRecordGoalUi(
            userName = currentGoalConfig.userName.orEmpty(),
            avatar = currentGoalConfig.avatar,
            todayText = getString(R.string.read_record_goal_today, todayText),
            totalText = getString(R.string.read_record_goal_total, totalText),
            booksText = getString(R.string.read_record_goal_books, readBookCount),
            progressText = getString(
                R.string.read_record_goal_target_progress,
                todayText,
                formatDuring(goalMs)
            ),
            progressPercent = percent
        )
    }

    private fun buildRecentBookMeta(book: Book): String {
        val parts = mutableListOf<String>()
        book.durChapterTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += getString(R.string.read_record_current_chapter, it)
        }
        parts += getString(
            R.string.read_record_last_open,
            lastOpenFormatter.format(Date(book.durChapterTime))
        )
        return parts.joinToString(" 路 ")
    }

    private fun buildDaySubtitle(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> getString(R.string.read_record_today_word)
            today.minusDays(1) -> getString(R.string.read_record_yesterday_word)
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
    }

    private fun applyPageChrome() {
        val panelSurfaceColor = requireContext().themeCardColorOrDefault()

        binding.panelOverview.background = null
        listOf(
            binding.panelHeatmap,
            binding.panelRecentBooks,
            binding.panelDailyRecords
        ).forEach { panel ->
            panel.background = createSurfaceDrawable(panelSurfaceColor, 14f)
        }
        binding.tvRecordDate.setTextColor(primaryTextColor)
        binding.tvRecordDateHint.setTextColor(secondaryTextColor)
        binding.tvHeatmapSubtitle.setTextColor(secondaryTextColor)
        binding.tvHeatmapEmpty.setTextColor(secondaryTextColor)
        binding.tvRecentBooksEmpty.setTextColor(secondaryTextColor)
        binding.tvDailyRecordsEmpty.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthStart.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthCenter.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthEnd.setTextColor(secondaryTextColor)
        binding.panelRecentCovers.background =
            createSurfaceDrawable(panelSurfaceColor, 14f)
        binding.panelReadRank.background =
            createSurfaceDrawable(panelSurfaceColor, 14f)
        binding.panelGoalCard.background =
            createSurfaceDrawable(panelSurfaceColor, 14f)
        binding.ivComponentMenu.background = null
        binding.ivComponentMenu.setColorFilter(primaryTextColor)
        binding.ivRankMore.background = null
        binding.ivGoalEdit.background = null
        binding.ivRankMore.setColorFilter(secondaryTextColor)
        binding.ivGoalEdit.setColorFilter(secondaryTextColor)
        binding.heatmapView.submit(currentHeatmapCells, accentColor, panelSurfaceColor)
    }

    private fun createDivider(): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply {
                marginStart = 15.dpToPx()
            }
            setBackgroundColor(ColorUtils.adjustAlpha(primaryTextColor, 0.08f))
        }
    }

    private fun createSurfaceDrawable(
        fillColor: Int,
        radiusDp: Float
    ): Drawable {
        return UiCorner.panelInsetStrokeDrawable(
            requireContext(),
            fillColor,
            UiCorner.scaledDp(radiusDp)
        )
    }

    private fun formatDuring(mss: Long): String {
        val days = mss / (1000 * 60 * 60 * 24)
        val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
        val seconds = mss % (1000 * 60) / 1000
        val d = if (days > 0) getString(R.string.duration_day, days) else ""
        val h = if (hours > 0) getString(R.string.duration_hour, hours) else ""
        val m = if (minutes > 0) getString(R.string.duration_minute, minutes) else ""
        val s = if (seconds > 0 && days == 0L && hours == 0L) {
            getString(R.string.duration_second, seconds)
        } else {
            ""
        }
        val time = "$d$h$m$s"
        return if (time.isBlank()) getString(R.string.duration_zero) else time
    }

}

private const val DATA_STALE_MS = 60_000L
private const val requestGoalAvatar = 501

private data class ExportBundle(
    val summary: ReadRecordExporter.Summary,
    val days: List<ReadRecordExporter.Day>,
    val books: List<ReadRecordExporter.Book>,
    val exportedAt: String
)

private data class ReadRecordDashboard(
    val today: LocalDate,
    val todayTime: Long,
    val monthTime: Long,
    val totalTime: Long,
    val activeDays: Int,
    val heatmapCells: List<ReadHeatmapCell>,
    val recentBooks: List<RecentReadBook>,
    val dailyTimeline: List<DailyReadSummary>,
    val hasDailyStats: Boolean,
    val recentCoverItems: List<ReadRecentVisualItem>,
    val rankItems: List<ReadRecordRankItem>,
    val goalConfig: ReadRecordGoalConfig,
    val readBookCount: Int,
    val latestRecentReadTime: Long
)

private data class RecentReadBook(
    val book: Book,
    val totalReadTime: Long
)

private data class DailyReadSummary(
    val date: LocalDate,
    val readTime: Long
)
