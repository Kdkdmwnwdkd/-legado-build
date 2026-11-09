package com.shimmer.reader.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseDialogFragment
import com.shimmer.reader.data.appDb
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.data.entities.BookChapter
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.databinding.DialogRecyclerViewBinding
import com.shimmer.reader.databinding.ItemReviewDetailBinding
import com.shimmer.reader.databinding.ItemReviewReplyBinding
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.model.analyzeRule.AnalyzeUrl
import com.shimmer.reader.model.analyzeRule.ReviewRuleParser
import com.shimmer.reader.model.jsSource.JsSourceReview
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.setLayout
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding

/**
 * 段评详情
 *
 * 数据链完全走书源规则：
 * - 普通源：段落序号 + 段内数据 → [AnalyzeUrl] 请求详情页 → [ReviewRuleParser.parseDetailPage]
 * - JS 源：交给 [JsSourceReview.getReviewDetailAwait]
 */
class ReviewDetailDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    constructor(
        chapterIndex: Int,
        paragraphNum: Int,
        paragraphData: String,
        totalCount: Int,
    ) : this() {
        arguments = Bundle().apply {
            putInt("chapterIndex", chapterIndex)
            putInt("paragraphNum", paragraphNum)
            putString("paragraphData", paragraphData)
            putInt("totalCount", totalCount)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { ReviewDetailAdapter(::expandReplies) }

    private var book: Book? = null
    private var source: BookSource? = null
    private var chapter: BookChapter? = null
    private var paragraphNum: Int = 0
    private var paragraphData: String = ""
    private var totalCount: Int = 0

    private var nextPageUrl: String? = null
    private var currentPage: Int = 0
    private var hasMore: Boolean = true
    private var isLoading: Boolean = false
    private var hasReplyUrl: Boolean = false

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: return
        paragraphNum = args.getInt("paragraphNum")
        paragraphData = args.getString("paragraphData").orEmpty()
        totalCount = args.getInt("totalCount")
        val chapterIndex = args.getInt("chapterIndex")
        book = ReadBook.book
        source = ReadBook.bookSource
        chapter = book?.let { appDb.bookChapterDao.getChapter(it.bookUrl, chapterIndex) }
        if (book == null || source == null || chapter == null) {
            dismissAllowingStateLoss()
            return
        }
        setupToolbar()
        setupRecycler()
        loadPage(1, append = false)
    }

    private fun setupToolbar() {
        binding.toolBar.title = buildString {
            append(getString(R.string.review_detail_title))
            if (totalCount > 0) append(" ($totalCount)")
        }
        binding.toolBar.setNavigationIcon(R.drawable.ic_baseline_close)
        binding.toolBar.setNavigationOnClickListener { dismiss() }
        binding.tvFooterLeft.visibility = View.GONE
        binding.tvCancel.visibility = View.GONE
        binding.tvOk.visibility = View.GONE
    }

    private fun setupRecycler() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading || !hasMore) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    loadPage(currentPage + 1, append = true)
                }
            }
        })
    }

    private fun loadPage(page: Int, append: Boolean) {
        if (isLoading) return
        val book = book ?: return
        val source = source ?: return
        val chapter = chapter ?: return
        if (!append) {
            currentPage = 0
            nextPageUrl = null
            hasMore = true
            adapter.submitList(emptyList())
            binding.rotateLoading.visibility = View.VISIBLE
            binding.tvMsg.visibility = View.GONE
        }
        if (!hasMore) return
        isLoading = true
        execute {
            if (source.isJsSource()) {
                JsSourceReview.getReviewDetailAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphNum,
                    paragraphData = paragraphData,
                    page = page,
                )?.let {
                    return@execute LoadResult(
                        items = it.items,
                        nextPageUrl = it.nextPageUrl,
                        hasNextPageRule = true,
                        hasReplyUrl = JsSourceReview.hasReviewRepliesCapability(source),
                    )
                }
                return@execute null
            }
            val rule = source.ruleReview
            if (rule == null || !rule.enabled) return@execute null
            val firstPageUrlRule = rule.reviewDetailUrl?.takeIf { it.isNotBlank() }
                ?: return@execute null
            val nextPageUrlRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
            val effectiveNextUrl = nextPageUrl?.takeIf { it.isNotBlank() }
            if (page > 1 && effectiveNextUrl == null && nextPageUrlRule == null) {
                return@execute null
            }
            val detailUrlRule = when {
                page > 1 && !effectiveNextUrl.isNullOrBlank() -> effectiveNextUrl
                page > 1 -> nextPageUrlRule ?: firstPageUrlRule
                else -> firstPageUrlRule
            }
            if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
                return@execute null
            }
            val analyzeUrl = AnalyzeUrl(
                mUrl = detailUrlRule,
                page = page,
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
                extraParams = mapOf(
                    "paraIndex" to paragraphNum.toString(),
                    "paraData" to paragraphData,
                    "page" to page.toString(),
                ),
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: ""
            val result = ReviewRuleParser.parseDetailPage(
                body = body,
                rule = rule,
                nextPageRule = nextPageUrlRule,
                baseUrl = analyzeUrl.url,
                source = source,
                book = book,
                chapter = chapter,
                context = coroutineContext,
                paraIndex = paragraphNum.toString(),
                paraData = paragraphData,
                page = page.toString(),
            )
            LoadResult(
                items = result.items,
                nextPageUrl = result.nextPageUrl,
                hasNextPageRule = nextPageUrlRule != null,
                hasReplyUrl = !rule.reviewQuoteUrl.isNullOrBlank() &&
                    !rule.replyListRule.isNullOrBlank() &&
                    !rule.replyContentRule.isNullOrBlank(),
            )
        }.onSuccess {
            isLoading = false
            binding.rotateLoading.visibility = View.GONE
            if (it == null) {
                if (!append) showEmpty()
                return@onSuccess
            }
            hasReplyUrl = it.hasReplyUrl
            if (it.hasNextPageRule) {
                nextPageUrl = it.nextPageUrl
                if (it.nextPageUrl.isNullOrBlank()) hasMore = false
            }
            if (it.items.isEmpty()) {
                hasMore = false
                if (!append) showEmpty()
                return@onSuccess
            }
            currentPage = page
            val list = if (append) adapter.currentList + it.items.map(::ReviewUiItem)
            else it.items.map(::ReviewUiItem)
            adapter.submitList(list)
        }.onError {
            isLoading = false
            binding.rotateLoading.visibility = View.GONE
            if (!append) {
                binding.tvMsg.text = it.localizedMessage ?: getString(R.string.content_empty)
                binding.tvMsg.visibility = View.VISIBLE
            }
        }
    }

    private fun showEmpty() {
        binding.tvMsg.text = getString(R.string.content_empty)
        binding.tvMsg.visibility = View.VISIBLE
    }

    /**
     * 展开某条段评的回复（书源配置了 reviewQuoteUrl + replyListRule 时）
     */
    private fun expandReplies(position: Int) {
        val uiItem = adapter.currentList.getOrNull(position) ?: return
        if (uiItem.repliesExpanded) {
            adapter.setRepliesExpanded(position, false)
            return
        }
        if (uiItem.loaded) {
            adapter.setRepliesExpanded(position, true)
            return
        }
        if (!hasReplyUrl) {
            adapter.setReplies(position, emptyList())
            return
        }
        val book = book ?: return
        val source = source ?: return
        val chapter = chapter ?: return
        val reviewId = uiItem.item.id
        adapter.setRepliesExpanded(position, true, loading = true)
        execute {
            if (source.isJsSource()) {
                if (reviewId.isNullOrBlank()) return@execute null
                JsSourceReview.getReviewRepliesAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphNum,
                    paragraphData = paragraphData,
                    reviewId = reviewId,
                    page = 1,
                )
            } else {
                val rule = source.ruleReview ?: return@execute null
                val quoteUrlRule = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() }
                    ?: return@execute null
                val analyzeUrl = AnalyzeUrl(
                    mUrl = quoteUrlRule,
                    baseUrl = chapter.url,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = coroutineContext,
                    extraParams = mapOf(
                        "paraIndex" to paragraphNum.toString(),
                        "paraData" to paragraphData,
                        "reviewId" to reviewId.orEmpty(),
                        "page" to "1",
                    ),
                )
                val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: ""
                ReviewRuleParser.parseReplyPage(
                    body = body,
                    rule = rule,
                    baseUrl = analyzeUrl.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = coroutineContext,
                    paraIndex = paragraphNum.toString(),
                    paraData = paragraphData,
                    page = "1",
                )
            }
        }.onSuccess { replies ->
            adapter.setReplies(position, replies.orEmpty())
        }.onError {
            adapter.setReplies(position, emptyList())
        }
    }

    private data class LoadResult(
        val items: List<ReviewRuleParser.DetailItem>,
        val nextPageUrl: String?,
        val hasNextPageRule: Boolean,
        val hasReplyUrl: Boolean,
    )

    companion object {
        const val TAG = "ReviewDetailDialog"

        fun show(
            fragmentManager: FragmentManager,
            chapterIndex: Int,
            paragraphNum: Int,
            paragraphData: String,
            totalCount: Int,
        ) {
            if (fragmentManager.isStateSaved) return
            ReviewDetailDialog(chapterIndex, paragraphNum, paragraphData, totalCount)
                .show(fragmentManager, TAG)
        }
    }
}

private data class ReviewUiItem(
    val item: ReviewRuleParser.DetailItem,
    val replies: List<ReviewRuleParser.DetailItem> = emptyList(),
    val repliesExpanded: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
)

private class ReviewDetailAdapter(
    private val onExpandReplies: (Int) -> Unit,
) : ListAdapter<ReviewUiItem, ReviewDetailAdapter.Holder>(DIFF) {

    fun setReplies(position: Int, replies: List<ReviewRuleParser.DetailItem>) {
        update(position) {
            it.copy(replies = replies, repliesExpanded = true, loaded = true, loading = false)
        }
    }

    fun setRepliesExpanded(position: Int, expanded: Boolean, loading: Boolean = false) {
        update(position) { it.copy(repliesExpanded = expanded, loading = loading) }
    }

    private fun update(position: Int, block: (ReviewUiItem) -> ReviewUiItem) {
        val list = currentList.toMutableList()
        val old = list.getOrNull(position) ?: return
        list[position] = block(old)
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemReviewDetailBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemReviewDetailBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(uiItem: ReviewUiItem) {
            val item = uiItem.item
            binding.tvName.text = item.name?.takeIf { it.isNotBlank() }
                ?: binding.root.context.getString(R.string.anonymous)
            binding.tvTime.text = item.time.orEmpty()
            val badgeText = item.badges.filter { it.isNotBlank() }.joinToString(" · ")
            binding.tvBadge.text = badgeText
            binding.tvBadge.visibility = if (badgeText.isBlank()) View.GONE else View.VISIBLE

            if (item.content.isNullOrBlank()) {
                binding.tvContent.visibility = View.GONE
            } else {
                binding.tvContent.visibility = View.VISIBLE
                binding.tvContent.text = item.content
            }

            if (item.avatar.isNullOrBlank()) {
                binding.ivAvatar.setImageResource(R.drawable.ic_read_record_default_avatar)
            } else {
                Glide.with(binding.ivAvatar)
                    .load(item.avatar)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_read_record_default_avatar)
                    .error(R.drawable.ic_read_record_default_avatar)
                    .into(binding.ivAvatar)
            }

            if (item.imageUrl.isNullOrBlank()) {
                binding.ivImage.visibility = View.GONE
            } else {
                binding.ivImage.visibility = View.VISIBLE
                Glide.with(binding.ivImage)
                    .load(item.imageUrl)
                    .transform(RoundedCorners(6.dpToPx()))
                    .into(binding.ivImage)
            }

            binding.tvLike.text = item.likeCount?.takeIf { it > 0 }?.let { "👍 $it" }.orEmpty()
            binding.tvLike.visibility =
                if (binding.tvLike.text.isBlank()) View.GONE else View.VISIBLE

            val replyCount = item.replyCount ?: item.replies.size
            if (replyCount > 0) {
                binding.tvReplyCount.visibility = View.VISIBLE
                binding.tvReplyCount.text = binding.root.context.getString(
                    R.string.review_reply_count, replyCount
                )
                binding.tvReplyCount.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onExpandReplies(pos)
                }
            } else {
                binding.tvReplyCount.visibility = View.GONE
            }

            binding.llReplies.removeAllViews()
            if (uiItem.repliesExpanded) {
                binding.llReplies.visibility = View.VISIBLE
                when {
                    uiItem.loading -> binding.llReplies.addView(createReplyPlaceholder("…"))
                    uiItem.replies.isEmpty() -> binding.llReplies.addView(
                        createReplyPlaceholder(
                            binding.root.context.getString(R.string.review_reply_empty)
                        )
                    )
                    else -> uiItem.replies.forEach { reply ->
                        binding.llReplies.addView(createReplyView(reply))
                    }
                }
            } else {
                binding.llReplies.visibility = View.GONE
            }
        }

        private fun createReplyView(reply: ReviewRuleParser.DetailItem): View {
            val replyBinding = ItemReviewReplyBinding.inflate(
                LayoutInflater.from(binding.root.context), binding.llReplies, false
            )
            replyBinding.tvName.text = reply.name.orEmpty()
            replyBinding.tvContent.text = reply.content.orEmpty()
            if (reply.avatar.isNullOrBlank()) {
                replyBinding.ivAvatar.setImageResource(R.drawable.ic_read_record_default_avatar)
            } else {
                Glide.with(replyBinding.ivAvatar)
                    .load(reply.avatar)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_read_record_default_avatar)
                    .error(R.drawable.ic_read_record_default_avatar)
                    .into(replyBinding.ivAvatar)
            }
            return replyBinding.root
        }

        private fun createReplyPlaceholder(text: String): TextView {
            return TextView(binding.root.context).apply {
                this.text = text
                textSize = 12f
                setTextColor(context.getColor(R.color.secondaryText))
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ReviewUiItem>() {
            override fun areItemsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
                val oldId = oldItem.item.id
                val newId = newItem.item.id
                if (!oldId.isNullOrBlank() && !newId.isNullOrBlank()) return oldId == newId
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
