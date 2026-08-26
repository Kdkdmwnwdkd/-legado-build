package io.legado.app.ui.about

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.app.DatePickerDialog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.registerForActivityResult
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class ReadRecordActivity : BaseActivity<ActivityReadRecordBinding>() {

    override val binding by viewBinding(ActivityReadRecordBinding::inflate)

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
    private var componentItems = ReadRecordComponents.load()
    private var currentRankItems: List<ReadRecordRankItem> = emptyList()
    private var currentGoalConfig: ReadRecordGoalConfig = ReadRecordWidgetStore.loadGoalConfig()
    private var currentTodayTime: Long = 0L
    private var currentTotalTime: Long = 0L
    private var currentReadBookCount: Int = 0
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read_record, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_record)?.isChecked = AppConfig.enableReadRecord
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_record -> {
                AppConfig.enableReadRecord = !item.isChecked
                invalidateOptionsMenu()
                return true
            }

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
                            loadData()
                        }
                    }
                    noButton()
                }
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.scrollView.applyNavigationBarPadding()
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
        binding.tvRecordDate.setOnClickListener {
            showDatePicker()
        }
        binding.ivComponentMenu.setOnClickListener {
            showComponentConfigDialog()
        }
        binding.ivRankMore.setOnClickListener {
            ReadRecordRankDialog.show(this, currentRankItems, ::formatDuring) { item ->
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDao.deleteByName(item.displayName)
                    }
                    loadData()
                }
            }
        }
        binding.ivGoalEdit.setOnClickListener {
            showReadRecordGoalDialog(
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
        applyComponentLayout()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val dashboard = withContext(IO) {
                buildDashboard()
            }
            renderDashboard(dashboard)
        }
    }

    private fun startAvatarCrop(uri: Uri) {
        val request = ImageCropHelper.buildRequest(
            context = this,
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

    private fun buildDashboard(): ReadRecordDashboard {
        val today = selectedDate
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

    private fun showComponentConfigDialog() {
        ReadRecordComponentConfigDialog.show(this, componentItems) { items ->
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
            this,
            { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                loadData()
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
        showReadRecordBookActionDialog(item.book.name, item.book, item.book.name) {
            lifecycleScope.launch {
                withContext(IO) {
                    appDb.readRecentBookDao.deleteSameBook(item.book.name, item.book.author)
                    ReadRecordWidgetStore.removeRecentSnapshot(item.book)
                }
                loadData()
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
                    loadData()
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
            openReadRecordBook(it.book, it.snapshot.name)
        }
    }

    private fun showRecentCoverActions(index: Int) {
        val item = currentRecentCovers.getOrNull(index) ?: return
        showReadRecordBookActionDialog(
            title = item.book?.name ?: item.snapshot.name,
            book = item.book,
            fallbackName = item.snapshot.name
        ) {
            lifecycleScope.launch {
                withContext(IO) {
                    item.book?.let { book ->
                        appDb.readRecentBookDao.deleteSameBook(book.name, book.author)
                        ReadRecordWidgetStore.removeRecentSnapshot(book)
                    } ?: run {
                        appDb.readRecentBookDao.delete(item.snapshot.bookUrl)
                        ReadRecordWidgetStore.removeRecentSnapshot(item.snapshot.bookUrl)
                    }
                }
                loadData()
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
            openReadRecordBook(it.book, it.displayName)
        }
    }

    private fun showRankBookActions(index: Int) {
        val item = currentVisibleRankItems.getOrNull(index) ?: return
        showReadRecordBookActionDialog(
            title = item.book?.name ?: item.snapshot?.name ?: item.displayName,
            book = item.book,
            fallbackName = item.displayName
        ) {
            lifecycleScope.launch {
                withContext(IO) {
                    appDb.readRecordDao.deleteByName(item.displayName)
                }
                loadData()
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
        return parts.joinToString(" · ")
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
        val panelSurfaceColor = themeCardColorOrDefault()

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
        return View(this).apply {
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
            this,
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

    companion object {
        private const val requestGoalAvatar = 501
    }

}

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
