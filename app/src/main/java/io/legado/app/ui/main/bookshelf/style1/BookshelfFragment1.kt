@file:Suppress("DEPRECATION")

package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.compose.ComposeMultiChoiceDialog
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.isCreated
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch

/**
 * 书架界面
 */
class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_bookshelf1) {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)
    private val adapter by lazy { TabFragmentPageAdapter(childFragmentManager) }
    private val bookGroups = mutableListOf<BookGroup>()
    private val fragmentMap = hashMapOf<Long, BooksFragment>()
    private var groupMenuPopup: ModernActionPopup.Handle? = null
    private var bookTags = emptyList<String>()
    private var selectedBookTag = ""
    private val groupShelfCache = hashMapOf<Long, List<BookShelfDisplay>>()
    private var currentGroupIndex = 0
    private var topOverlaySpace = 0
    private var topOverlayEnabled = false
    private var structureVersion = 0
    override val groupId: Long get() = selectedGroup?.groupId ?: 0

    override val books: List<Book>
        get() {
            val fragment = fragmentMap[groupId]
            return fragment?.getBooks() ?: emptyList()
        }

    override var onlyUpdateRead = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initBookGroupData()
    }

    private val selectedGroup: BookGroup?
        get() = bookGroups.getOrNull(binding.viewPagerBookshelf.currentItem)

    private fun initView() {
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.viewPagerBookshelf.setEdgeEffectColor(primaryColor)
        binding.topBar.setMode(MainTopBarView.Mode.BOOKSHELF)
        binding.topBar.moreButton.setOnClickListener {
            showModernBookshelfMenu(it)
        }
        binding.topBar.searchButton.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SearchActivity::class.java))
        }
        binding.topBar.setSearchHint(getString(R.string.search_book_key))
        binding.topBar.searchEntry.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SearchActivity::class.java))
        }
        binding.topBar.setOnHeightChangedListener {
            updateTopBarOverlay()
        }
        binding.topBar.titleSelect.setOnClickListener {
            showGroupSwitchMenu(it)
        }
        binding.topBar.primaryBar.setOnTagClickListener { index ->
            selectedBookTag = ""
            switchToGroup(index)
        }
        binding.topBar.showTags(true)
        binding.topBar.tagsBar.setOnTagClickListener { index ->
            val tag = bookTags.getOrNull(index).orEmpty()
            if (tag == selectedBookTag) {
                selectedGroup?.let { group ->
                    fragmentMap[group.groupId]?.let { fragment ->
                        val label = tag.ifBlank { getString(R.string.bookshelf_tag_all) }
                        toastOnUi("${group.groupName} · $label(${fragment.getBooksCount()})")
                    }
                }
            } else {
                selectedBookTag = tag
                binding.topBar.tagsBar.setSelectedIndex(index, smooth = true)
                fragmentMap[groupId]?.setBookTagFilter(tag)
            }
        }
        binding.topBar.tagsBar.setOnTagLongClickListener { index ->
            selectedBookTag = bookTags.getOrNull(index).orEmpty()
            fragmentMap[groupId]?.setBookTagFilter(selectedBookTag)
            true
        }
        binding.viewPagerBookshelf.offscreenPageLimit = 1
        binding.viewPagerBookshelf.swipeEnabled = AppConfig.bottomBarLayoutMode != "sidebar"
        binding.viewPagerBookshelf.adapter = adapter
        binding.viewPagerBookshelf.addOnPageChangeListener(
            object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    if (position !in bookGroups.indices) return
                    currentGroupIndex = position
                    selectedBookTag = ""
                    updateHeaderTitle()
                    val group = bookGroups[position]
                    fragmentMap[group.groupId]?.setBookTagFilter("")
                    renderBookTags(groupShelfCache[group.groupId].orEmpty())
                }
            }
        )
        binding.topBar.doOnLayout {
            updateTopBarOverlay()
        }
        binding.root.post {
            updateTopBarOverlay()
        }
        updateHeaderTitle()
    }

    override fun onResume() {
        super.onResume()
        binding.viewPagerBookshelf.swipeEnabled = AppConfig.bottomBarLayoutMode != "sidebar"
        binding.root.post {
            updateTopBarOverlay()
        }
    }

    private fun updateTopBarOverlay() {
        if (!isAdded) return
        // 仅 API>=33 走覆盖式(顶栏背景毛玻璃);低版本降级为非覆盖布局,避免透明顶栏透出滚动列表。
        val overlay = binding.topBar.isOverlayMode() && binding.topBar.supportsBackdropBlur()
        val contentMargin = resources.getDimensionPixelSize(R.dimen.bookshelf_content_margin_top)
        val newSpace = if (overlay) binding.topBar.height else 0
        if (topOverlayEnabled != overlay) {
            ConstraintSet().apply {
                clone(binding.root)
                clear(R.id.view_pager_bookshelf, ConstraintSet.TOP)
                if (overlay) {
                    connect(
                        R.id.view_pager_bookshelf,
                        ConstraintSet.TOP,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.TOP
                    )
                    setMargin(R.id.view_pager_bookshelf, ConstraintSet.TOP, 0)
                } else {
                    connect(
                        R.id.view_pager_bookshelf,
                        ConstraintSet.TOP,
                        R.id.top_bar,
                        ConstraintSet.BOTTOM
                    )
                    setMargin(R.id.view_pager_bookshelf, ConstraintSet.TOP, contentMargin)
                }
                applyTo(binding.root)
            }
        }
        topOverlayEnabled = overlay
        topOverlaySpace = newSpace
        fragmentMap.values.forEach { fragment ->
            fragment.setTopOverlaySpace(topOverlaySpace, topOverlayEnabled)
        }
        binding.topBar.setBackdropBlur(null)
        binding.topBar.bringToFront()
    }

    private fun scheduleTopBarOverlayUpdate() {
        if (!isAdded) return
        binding.topBar.post {
            updateTopBarOverlay()
        }
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
        } else if (data != bookGroups) {
            bookGroups.clear()
            bookGroups.addAll(data)
            adapter.notifyDataSetChanged()
            renderGroupSelector()
            selectSavedGroup()
        } else {
            renderGroupSelector()
            renderGroupTags()
        }
        updateHeaderTitle()
    }

    override fun upSort() {
        adapter.notifyDataSetChanged()
    }

    private fun selectSavedGroup() {
        binding.viewPagerBookshelf.post {
            if (bookGroups.isEmpty()) {
                binding.topBar.setPrimaryItems(emptyList(), -1)
                binding.topBar.tagsBar.submitItems(emptyList(), -1)
                updateHeaderTitle()
                return@post
            }
            val target = AppConfig.saveTabPosition.coerceIn(0, bookGroups.lastIndex)
            switchToGroup(target)
        }
    }

    override fun gotoTop() {
        fragmentMap[groupId]?.gotoTop()
    }

    private fun renderGroupTags() {
        renderBookTags(emptyList())
    }

    private fun updateHeaderTitle() {
        binding.topBar.setTitle(selectedGroup?.groupName ?: getString(R.string.bookshelf))
        renderGroupSelector()
    }

    private fun renderGroupSelector() {
        binding.topBar.setPrimaryItems(
            bookGroups.map { RoundedTagBarView.Item(it.groupName) },
            currentGroupIndex.coerceIn(-1, bookGroups.lastIndex)
        )
        scheduleTopBarOverlayUpdate()
    }

    fun onBooksChanged(groupId: Long, books: List<Book>) {
        onShelfDisplaysChanged(groupId, books.map { it.toShelfDisplay() })
    }

    fun onShelfDisplaysChanged(groupId: Long, books: List<BookShelfDisplay>) {
        groupShelfCache[groupId] = books
        if (groupId != this.groupId) return
        renderBookTags(books)
    }

    private fun renderBookTags(books: List<BookShelfDisplay>) {
        if (!isAdded) return
        val allText = getString(R.string.bookshelf_tag_all)
        val storedTags = AppConfig.bookshelfGroupTags[groupId].orEmpty()
        val tags = storedTags.ifEmpty {
            val migratedTags = books.asSequence()
                .flatMap { BookTagHelper.parse(it.customTag).asSequence() }
                .distinct()
                .sorted()
                .toList()
            if (migratedTags.isNotEmpty()) {
                val map = AppConfig.bookshelfGroupTags.toMutableMap()
                map[groupId] = migratedTags
                AppConfig.bookshelfGroupTags = map
            }
            migratedTags
        }
            .filterNot { it in AppConfig.bookshelfHiddenTags[groupId].orEmpty() }
        bookTags = listOf("") + tags
        if (selectedBookTag.isNotBlank() && selectedBookTag !in tags) {
            selectedBookTag = ""
            fragmentMap[groupId]?.setBookTagFilter("")
        }
        binding.topBar.tagsBar.submitItems(
            bookTags.map { RoundedTagBarView.Item(it.ifBlank { allText }) },
            bookTags.indexOf(selectedBookTag).takeIf { it >= 0 } ?: 0
        )
        scheduleTopBarOverlayUpdate()
    }

    private fun showGroupSwitchMenu(anchor: View) {
        if (bookGroups.isEmpty()) return
        groupMenuPopup?.dismiss()
        val selectedId = selectedGroup?.groupId
        val actions = bookGroups.mapIndexed { index, group ->
            val prefix = if (group.groupId == selectedId) "✓ " else ""
            ModernActionPopup.Action(prefix + group.groupName) {
                selectedBookTag = ""
                switchToGroup(index)
            }
        }
        groupMenuPopup = ModernActionPopup.show(anchor, actions, groupMenuPopup)
    }

    private fun switchToGroup(index: Int) {
        if (index !in bookGroups.indices) return
        currentGroupIndex = index
        binding.viewPagerBookshelf.setCurrentItem(index, false)
        AppConfig.saveTabPosition = index
        selectedBookTag = ""
        fragmentMap[groupId]?.setBookTagFilter("")
        renderBookTags(groupShelfCache[groupId].orEmpty())
        updateHeaderTitle()
    }

    fun switchToGroupId(groupId: Long) {
        val index = bookGroups.indexOfFirst { it.groupId == groupId }
        if (index >= 0) {
            switchToGroup(index)
        }
    }

    override fun showBookTagManageAlert() {
        val group = selectedGroup ?: return
        val targetBooks = groupShelfCache[group.groupId].orEmpty()
        val tags = targetBooks
            .flatMap { BookTagHelper.parse(it.customTag) }
            .distinct()
            .sorted()
        if (tags.isEmpty()) {
            toastOnUi(R.string.bookshelf_tag_none)
            return
        }
        val checked = BooleanArray(tags.size) { true }
        val labels = tags.map { tag ->
            "$tag (${targetBooks.count { BookTagHelper.has(it.customTag, tag) }})"
        }
        showDialogFragment(
            ComposeMultiChoiceDialog.create(
                title = "${group.groupName} · ${getString(R.string.bookshelf_tag_manage)}",
                labels = labels,
                checked = checked,
                message = getString(R.string.bookshelf_tag_manage_hint),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                onPositive = { result ->
                    val keepTags = tags.filterIndexed { index, _ -> result[index] }.toSet()
                lifecycleScope.launch(IO) {
                    val fullBooks = appDb.bookDao.getBooksSafe(targetBooks.map { it.bookUrl })
                    fullBooks.forEach { book ->
                        val normalized = BookTagHelper.join(
                            BookTagHelper.parse(book.customTag).filter { it in keepTags }
                        )
                        if (normalized != book.customTag) {
                            book.customTag = normalized
                            appDb.bookDao.update(book)
                        }
                    }
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                }
                }
            )
        )
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            renderBookTags(groupShelfCache[groupId].orEmpty())
        }
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            rebuildBookshelfContent()
        }
    }

    private fun rebuildBookshelfContent() {
        if (!isAdded) return
        val targetGroupId = groupId
        dismissBookshelfTransientUi()
        groupMenuPopup?.dismiss()
        groupMenuPopup = null
        structureVersion++
        fragmentMap.clear()
        selectedBookTag = ""
        binding.viewPagerBookshelf.post {
            if (!isAdded) return@post
            adapter.notifyDataSetChanged()
            if (bookGroups.any { it.groupId == targetGroupId }) {
                switchToGroupId(targetGroupId)
            } else {
                selectSavedGroup()
            }
            renderBookTags(groupShelfCache[groupId].orEmpty())
            updateTopBarOverlay()
        }
    }

    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getPageTitle(position: Int): CharSequence {
            return bookGroups[position].groupName
        }

        /**
         * 确定视图位置是否更改时调用
         * @return POSITION_NONE 已更改,刷新视图. POSITION_UNCHANGED 未更改,不刷新视图
         */
        override fun getItemPosition(any: Any): Int {
            val fragment = any as BooksFragment
            if (fragment.structureVersion != structureVersion) {
                return POSITION_NONE
            }
            val position = fragment.position
            val group = bookGroups.getOrNull(position)
            if (fragment.groupId != group?.groupId) {
                return POSITION_NONE
            }
            val bookSort = group.getRealBookSort()
            fragment.setEnableRefresh(group.enableRefresh)
            if (fragment.bookSort != bookSort) {
                fragment.upBookSort(bookSort)
            }
            return POSITION_UNCHANGED
        }

        override fun getItem(position: Int): Fragment {
            val group = bookGroups[position]
            onlyUpdateRead = group.onlyUpdateRead
            return BooksFragment(position, group)
        }

        override fun getCount(): Int {
            return bookGroups.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as BooksFragment
            val group = bookGroups[position]
            /**
             * Activity recreate 会复用之前的 Fragment，不正确的需要重新创建
             */
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as BooksFragment
            }
            fragmentMap[group.groupId] = fragment
            fragment.structureVersion = structureVersion
            fragment.setTopOverlaySpace(topOverlaySpace, topOverlayEnabled)
            return fragment
        }

    }

    private fun Book.toShelfDisplay(): BookShelfDisplay {
        return BookShelfDisplay(
            bookUrl = bookUrl,
            origin = origin,
            originName = originName,
            name = name,
            author = author,
            intro = intro,
            customIntro = customIntro,
            customTag = customTag,
            coverUrl = coverUrl,
            customCoverUrl = customCoverUrl,
            type = type,
            group = group,
            latestChapterTitle = latestChapterTitle,
            latestChapterTime = latestChapterTime,
            lastCheckCount = lastCheckCount,
            totalChapterNum = totalChapterNum,
            durChapterTitle = durChapterTitle,
            durChapterIndex = durChapterIndex,
            durChapterTime = durChapterTime,
            canUpdate = canUpdate,
            order = order,
            readConfig = readConfig
        )
    }
}
