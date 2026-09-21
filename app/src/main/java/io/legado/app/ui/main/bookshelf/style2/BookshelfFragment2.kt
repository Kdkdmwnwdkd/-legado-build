package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.compose.BookshelfBookItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfFolderItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfGridItem
import io.legado.app.ui.main.bookshelf.compose.BookshelfItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItem
import io.legado.app.ui.main.bookshelf.compose.BookshelfSnapshotStore
import io.legado.app.ui.main.bookshelf.compose.buildBookshelfItems
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.updateBookshelfItemUpdating
import io.legado.app.ui.widget.compose.ComposeLazyGridFastScroller
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private var bookshelfLayout by mutableIntStateOf(AppConfig.bookshelfLayout)
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var shelfDisplays: List<BookShelfDisplay> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private var bookshelfMargin by mutableIntStateOf(AppConfig.bookshelfMargin)
    private var itemCount = 0
    private var totalRows = 0
    private val useComposeGrid get() = bookshelfLayout >= 2
    private val useComposeList get() = bookshelfLayout < 2
    private val useComposeBookshelf get() = true
    private data class ComposeScrollPosition(val index: Int, val offset: Int)
    private val composeScrollPositions = mutableMapOf<Long, ComposeScrollPosition>()
    private var composeItems by mutableStateOf<List<BookshelfItemUi>>(emptyList())
    private var composeGroupId by mutableStateOf(BookGroup.IdRoot)
    private var composeDataVersion by mutableStateOf(0)
    private var composeCanScrollBackward by mutableStateOf(false)
    private var composePendingScrollRestoreGroupId by mutableStateOf<Long?>(null)
    private var composeScrollToTopTick by mutableStateOf(0)
    private var composeListItemStyle by mutableIntStateOf(AppConfig.bookshelfListItemStyle)
    private var composeListIntroLines by mutableIntStateOf(AppConfig.bookshelfListIntroLines)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        installModernBookshelfOverflow(binding.titleBar.toolbar)
        initRecyclerView()
        initComposeBookshelf()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        bindRefreshScrollCallback()
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }
        binding.rvBookshelf.isGone = true
        binding.composeBookshelf.isGone = false
        if (!useComposeBookshelf) {
            /**
             * 采用 layoutManager?.onRestoreInstanceState(layoutState)
             * 恢复滚动位置
             * **/
        }
    }

    private fun bindRefreshScrollCallback() {
        binding.refreshLayout.setOnChildScrollUpCallback { _, _ ->
            composeCanScrollBackward
        }
    }

    private fun initComposeBookshelf() {
        binding.composeBookshelf.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeBookshelf.setContent {
            if (useComposeGrid) {
                BookshelfGridContent()
            } else {
                BookshelfListContent()
            }
        }
    }

    @Composable
    private fun BookshelfGridContent() {
        val gridState = rememberLazyGridState()
        val currentGroupId = composeGroupId
        val pendingScrollRestoreGroupId = composePendingScrollRestoreGroupId
        val canScrollBackward by remember {
            derivedStateOf {
                gridState.firstVisibleItemIndex > 0 ||
                        gridState.firstVisibleItemScrollOffset > 0
            }
        }
        val marginDp = with(LocalDensity.current) { bookshelfMargin.toDp() }
        val bottomBarPadding = with(LocalDensity.current) {
            resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding).toDp()
        }
        DisposableEffect(currentGroupId) {
            onDispose {
                composeScrollPositions[currentGroupId] = ComposeScrollPosition(
                    index = gridState.firstVisibleItemIndex,
                    offset = gridState.firstVisibleItemScrollOffset
                )
            }
        }
        LaunchedEffect(canScrollBackward) {
            composeCanScrollBackward = canScrollBackward
        }
        LaunchedEffect(currentGroupId, pendingScrollRestoreGroupId, composeDataVersion) {
            if (pendingScrollRestoreGroupId == currentGroupId && composeDataVersion > 0) {
                val scrollPosition = composeScrollPositions[currentGroupId]
                if (scrollPosition != null && composeItems.isNotEmpty()) {
                    val targetIndex = scrollPosition.index.coerceAtMost(composeItems.lastIndex)
                    val targetOffset = if (targetIndex == scrollPosition.index) {
                        scrollPosition.offset
                    } else {
                        0
                    }
                    gridState.scrollToItem(targetIndex, targetOffset)
                } else {
                    gridState.scrollToItem(0)
                }
                composePendingScrollRestoreGroupId = null
            }
        }
        LaunchedEffect(composeScrollToTopTick) {
            if (composeScrollToTopTick > 0) {
                if (AppConfig.isEInkMode) {
                    gridState.scrollToItem(0)
                } else {
                    gridState.animateScrollToItem(0)
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(bookshelfLayout),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    top = marginDp + 24.dp,
                    end = 8.dp,
                    bottom = marginDp + bottomBarPadding + 12.dp
                )
            ) {
                items(
                    items = composeItems,
                    key = { it.key },
                    contentType = { it.contentType }
                ) { item ->
                    BookshelfGridItem(
                        item = item,
                        modifier = Modifier,
                        fragment = this@BookshelfFragment2,
                        lifecycle = viewLifecycleOwner.lifecycle,
                        onClick = ::onComposeItemClick,
                        onLongClick = ::onComposeItemLongClick
                    )
                }
            }
            ComposeLazyGridFastScroller(
                state = gridState,
                enabled = AppConfig.showBookshelfFastScroller,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }

    @Composable
    private fun BookshelfListContent() {
        val listState = rememberLazyListState()
        val currentGroupId = composeGroupId
        val pendingScrollRestoreGroupId = composePendingScrollRestoreGroupId
        val canScrollBackward by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 ||
                        listState.firstVisibleItemScrollOffset > 0
            }
        }
        val marginDp = with(LocalDensity.current) { bookshelfMargin.toDp() }
        val bottomBarPadding = with(LocalDensity.current) {
            resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding).toDp()
        }
        val renderConfig = rememberBookshelfListRenderConfig()
        DisposableEffect(currentGroupId) {
            onDispose {
                composeScrollPositions[currentGroupId] = ComposeScrollPosition(
                    index = listState.firstVisibleItemIndex,
                    offset = listState.firstVisibleItemScrollOffset
                )
            }
        }
        LaunchedEffect(canScrollBackward) {
            composeCanScrollBackward = canScrollBackward
        }
        LaunchedEffect(currentGroupId, pendingScrollRestoreGroupId, composeDataVersion) {
            if (pendingScrollRestoreGroupId == currentGroupId && composeDataVersion > 0) {
                val scrollPosition = composeScrollPositions[currentGroupId]
                if (scrollPosition != null && composeItems.isNotEmpty()) {
                    val targetIndex = scrollPosition.index.coerceAtMost(composeItems.lastIndex)
                    val targetOffset = if (targetIndex == scrollPosition.index) {
                        scrollPosition.offset
                    } else {
                        0
                    }
                    listState.scrollToItem(targetIndex, targetOffset)
                } else {
                    listState.scrollToItem(0)
                }
                composePendingScrollRestoreGroupId = null
            }
        }
        LaunchedEffect(composeScrollToTopTick) {
            if (composeScrollToTopTick > 0) {
                if (AppConfig.isEInkMode) {
                    listState.scrollToItem(0)
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    top = marginDp + 24.dp,
                    end = 8.dp,
                    bottom = marginDp + bottomBarPadding + 12.dp
                )
            ) {
                items(
                    items = composeItems,
                    key = { it.key },
                    contentType = { it.contentType }
                ) { item ->
                    BookshelfListItem(
                        item = item,
                        listLayout = bookshelfLayout,
                        cardStyle = composeListItemStyle,
                        introMaxLines = composeListIntroLines,
                        renderConfig = renderConfig,
                        modifier = Modifier.padding(vertical = marginDp.coerceAtLeast(2.dp)),
                        fragment = this@BookshelfFragment2,
                        lifecycle = viewLifecycleOwner.lifecycle,
                        onClick = ::onComposeItemClick,
                        onLongClick = ::onComposeItemLongClick
                    )
                }
            }
            ComposeLazyListFastScroller(
                state = listState,
                enabled = AppConfig.showBookshelfFastScroller,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            if (shelfDisplays.isEmpty() && composeItems.isEmpty()) {
                restoreComposeSnapshot(currentComposeSnapshotKey())
            } else {
                updateComposeItems(shelfDisplays)
                saveComposeSnapshot(currentComposeSnapshotKey(), composeItems)
            }
            itemCount = getItemCount()
            binding.tvEmptyMsg.isGone = itemCount > 0
            binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
                onlyUpdateRead = false
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            if (useComposeBookshelf) {
                val snapshotKey = currentComposeSnapshotKey()
                restoreComposeSnapshot(snapshotKey)
                appDb.bookDao.flowShelfByGroup(groupId).map { list ->
                    val sortedList = sortShelfDisplays(list, AppConfig.getBookSortByGroupId(groupId))
                    val items = buildBookshelfItems(
                        groups = bookGroups,
                        books = sortedList,
                        isRootGroup = groupId == BookGroup.IdRoot,
                        groupId = groupId,
                        isUpdating = ::isUpdate
                    )
                    sortedList to items
                }.flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME
                ).catch {
                    AppLog.put("涔︽灦鏇存柊鍑洪敊", it)
                }.conflate().flowOn(Dispatchers.Default).collect { (list, items) ->
                    shelfDisplays = list
                    books = list.map { it.toMinimalBook() }
                    composeItems = items
                    composeDataVersion++
                    itemCount = getItemCount()
                    binding.tvEmptyMsg.isGone = itemCount > 0
                    binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                    saveComposeSnapshot(snapshotKey, items)
                    delay(100)
                }
                return@launch
            }
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                books = list
                updateComposeItems()
                itemCount = getItemCount()
                val spanCount = bookshelfLayout
                if (!useComposeBookshelf && spanCount >= 2) {
                    totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                }
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                delay(100)
            }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            switchGroup(BookGroup.IdRoot)
            return true
        }
        return false
    }

    fun switchToGroupId(targetGroupId: Long) {
        switchGroup(targetGroupId)
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        composeScrollToTopTick++
    }

    private fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    private fun updateComposeItems() {
        updateComposeItems(shelfDisplays)
    }

    private fun updateComposeItems(list: List<BookShelfDisplay>) {
        composeItems = buildBookshelfItems(
            groups = bookGroups,
            books = list,
            isRootGroup = groupId == BookGroup.IdRoot,
            groupId = groupId,
            isUpdating = ::isUpdate
        )
        composeDataVersion++
    }

    private fun currentComposeSnapshotKey(): String {
        return BookshelfSnapshotStore.buildKey(
            style = "style2",
            groupId = groupId,
            sort = AppConfig.getBookSortByGroupId(groupId),
            tagFilter = "",
            groups = bookGroups
        )
    }

    private fun restoreComposeSnapshot(snapshotKey: String) {
        BookshelfSnapshotStore.getMemory(snapshotKey)?.let { items ->
            applyComposeSnapshot(snapshotKey, items)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(IO) {
            val items = BookshelfSnapshotStore.read(snapshotKey) ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isAdded || currentComposeSnapshotKey() != snapshotKey || composeItems.isNotEmpty()) {
                    return@withContext
                }
                applyComposeSnapshot(snapshotKey, items)
            }
        }
    }

    private fun applyComposeSnapshot(
        snapshotKey: String,
        items: List<BookshelfItemUi>
    ) {
        if (currentComposeSnapshotKey() != snapshotKey || items.isEmpty()) {
            return
        }
        composeItems = items
        shelfDisplays = items.mapNotNull { (it as? BookshelfBookItemUi)?.display }
        books = shelfDisplays.map { it.toMinimalBook() }
        composeDataVersion++
        itemCount = items.size
        binding.tvEmptyMsg.isGone = itemCount > 0
        binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
    }

    private fun saveComposeSnapshot(
        snapshotKey: String,
        items: List<BookshelfItemUi>
    ) {
        viewLifecycleOwner.lifecycleScope.launch(IO) {
            BookshelfSnapshotStore.save(snapshotKey, items)
        }
    }

    private fun onComposeItemClick(item: BookshelfItemUi) {
        when (item) {
            is BookshelfBookItemUi -> startActivityForBook(item.display.toMinimalBook())
            is BookshelfFolderItemUi -> {
                switchGroup(item.group.groupId)
            }
        }
    }

    private fun switchGroup(targetGroupId: Long) {
        if (groupId == targetGroupId) {
            return
        }
        groupId = targetGroupId
        if (useComposeBookshelf) {
            composeGroupId = targetGroupId
            composePendingScrollRestoreGroupId = targetGroupId
            composeItems = emptyList()
            composeDataVersion = 0
            composeCanScrollBackward = false
            binding.tvEmptyMsg.isGone = true
        }
        initBooksData()
    }

    private fun onComposeItemLongClick(item: BookshelfItemUi) {
        when (item) {
            is BookshelfBookItemUi -> lifecycleScope.launch {
                val book = withContext(IO) { appDb.bookDao.getBook(item.display.bookUrl) } ?: return@launch
                BookInfoNavigator.open(requireContext(), book)
            }
            is BookshelfFolderItemUi -> showDialogFragment(GroupEditDialog(item.group))
        }
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + books.size
        } else {
            books.size
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            updateComposeItemUpdating(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            bookshelfMargin = AppConfig.bookshelfMargin
            composeListItemStyle = AppConfig.bookshelfListItemStyle
            composeListIntroLines = AppConfig.bookshelfListIntroLines
            updateComposeItems()
        }
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            rebuildBookshelfContent()
        }
    }

    private fun rebuildBookshelfContent() {
        if (!isAdded) return
        val targetGroupId = groupId
        dismissBookshelfTransientUi()
        bookshelfLayout = AppConfig.bookshelfLayout.coerceIn(0, 6)
        bookshelfMargin = AppConfig.bookshelfMargin
        composeListItemStyle = AppConfig.bookshelfListItemStyle
        composeListIntroLines = AppConfig.bookshelfListIntroLines
        composeScrollPositions.clear()
        composeItems = emptyList()
        composeCanScrollBackward = false
        composePendingScrollRestoreGroupId = targetGroupId
        composeDataVersion = 0
        composeGroupId = targetGroupId
        binding.rvBookshelf.isGone = true
        binding.composeBookshelf.isGone = false
        bindRefreshScrollCallback()
        binding.tvEmptyMsg.isGone = true
        initBooksData()
    }

    private fun updateComposeItemUpdating(bookUrl: String) {
        composeItems = updateBookshelfItemUpdating(
            items = composeItems,
            bookUrl = bookUrl,
            isUpdating = ::isUpdate
        )
    }

    private fun sortShelfDisplays(
        list: List<BookShelfDisplay>,
        sort: Int
    ): List<BookShelfDisplay> {
        return when (sort) {
            1 -> list.sortedByDescending { it.latestChapterTime }
            2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
            3 -> list.sortedBy { it.order }
            4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> list.sortedByDescending { it.durChapterTime }
        }
    }
}
