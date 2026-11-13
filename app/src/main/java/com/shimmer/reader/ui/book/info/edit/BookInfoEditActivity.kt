package com.shimmer.reader.ui.book.info.edit

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.shimmer.reader.R
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.constant.BookType
import com.shimmer.reader.data.entities.Book
import com.shimmer.reader.databinding.ActivityBookInfoEditBinding
import com.shimmer.reader.help.book.BookTagHelper
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.help.book.addType
import com.shimmer.reader.help.book.isAudio
import com.shimmer.reader.help.book.isImage
import com.shimmer.reader.help.book.isLocal
import com.shimmer.reader.help.book.isVideo
import com.shimmer.reader.help.book.removeType
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.theme.applyUiBodyTypefaceDeep
import com.shimmer.reader.lib.theme.accentColor
import com.shimmer.reader.lib.theme.primaryTextColor
import com.shimmer.reader.lib.theme.secondaryTextColor
import com.shimmer.reader.lib.theme.uiTypeface
import com.shimmer.reader.lib.theme.view.ThemeEditText
import com.shimmer.reader.ui.book.changecover.ChangeCoverDialog
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.widget.image.CoverImageView
import com.shimmer.reader.utils.dpToPx
import com.shimmer.reader.utils.FileUtils
import com.shimmer.reader.utils.MD5Utils
import com.shimmer.reader.utils.externalFiles
import com.shimmer.reader.utils.inputStream
import com.shimmer.reader.utils.readUri
import com.shimmer.reader.utils.setOnApplyWindowInsetsListenerCompat
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.FileOutputStream

class BookInfoEditActivity :
    VMBaseActivity<ActivityBookInfoEditBinding, BookInfoEditViewModel>(),
    ChangeCoverDialog.CallBack {

    private val selectCover = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            coverChangeTo(uri)
        }
    }

    override val binding by viewBinding(ActivityBookInfoEditBinding::inflate)
    override val viewModel by viewModels<BookInfoEditViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bookData.observe(this) { upView(it) }
        if (viewModel.bookData.value == null) {
            intent.getStringExtra("bookUrl")?.let {
                viewModel.loadBook(it)
            }
        }
        initView()
        initEvent()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_info_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> saveData()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(typeMask)
            // The TitleBar owns the top inset; the form must also avoid side cutouts
            // and remain scrollable when the keyboard reduces its available height.
            view.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            windowInsets
        }
        binding.tvBookTags.setTextColor(primaryTextColor)
    }

    private fun initEvent() = binding.run {
        tvChangeCover.setOnClickListener {
            viewModel.bookData.value?.let {
                showDialogFragment(
                    ChangeCoverDialog(it.name, it.author)
                )
            }
        }
        tvSelectCover.setOnClickListener {
            selectCover.launch {
                mode = HandleFileContract.IMAGE
            }
        }
        tvRefreshCover.setOnClickListener {
            viewModel.book?.customCoverUrl = tieCoverUrl.text?.toString()
            upCover()
        }
        tvEditTags.setOnClickListener {
            viewModel.book?.let { showTagEditDialog(it) }
        }
    }

    private fun upView(book: Book) = binding.run {
        tieBookName.setText(book.name)
        tieBookAuthor.setText(book.author)
        spType.setSelection(
            when {
                book.isVideo -> 4
                book.isImage -> 2
                book.isAudio -> 1
                else -> 0
            }
        )
        tieCoverUrl.setText(book.getDisplayCover())
        tieBookIntro.setText(book.getDisplayIntro())
        upBookTags(book)
        upCover()
    }

    private fun upBookTags(book: Book) = binding.run {
        val tags = BookTagHelper.parse(book.customTag)
        tvBookTags.text = tags.joinToString(" · ").ifBlank {
            getString(R.string.bookshelf_tag_none)
        }
    }

    private fun showTagEditDialog(book: Book) {
        viewModel.loadTagCandidates(book) { candidates ->
            val currentTags = BookTagHelper.parse(book.customTag)
            val allTags = BookTagHelper.parse(BookTagHelper.join(currentTags + candidates))
            val checkBoxes = mutableListOf<Pair<String, CheckBox>>()
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 0)
            }
            if (allTags.isEmpty()) {
                container.addView(TextView(this).apply {
                    setText(R.string.bookshelf_tag_none)
                    setTextColor(secondaryTextColor)
                })
            } else {
                allTags.forEach { tag ->
                    val checkBox = AppCompatCheckBox(this).apply {
                        text = tag
                        isChecked = currentTags.any { it.equals(tag, ignoreCase = true) }
                        setTextColor(primaryTextColor)
                        minHeight = 48.dpToPx()
                        buttonTintList = android.content.res.ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(accentColor, secondaryTextColor)
                        )
                    }
                    checkBoxes += tag to checkBox
                    container.addView(checkBox)
                }
            }
            val newTagEdit = ThemeEditText(this).apply {
                hint = getString(R.string.bookshelf_tag_new_hint)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(false)
                minLines = 1
                setTextColor(primaryTextColor)
                setHintTextColor(secondaryTextColor)
            }
            container.addView(newTagEdit)
            container.applyUiBodyTypefaceDeep(uiTypeface())
            val scrollView = NestedScrollView(this).apply {
                isFillViewport = true
                addView(container)
            }
            alert(titleResource = R.string.bookshelf_tag_edit) {
                customView { scrollView }
                okButton {
                    val selected = checkBoxes
                        .filter { it.second.isChecked }
                        .map { it.first } + BookTagHelper.parse(newTagEdit.text?.toString())
                    book.customTag = BookTagHelper.join(selected)
                    upBookTags(book)
                }
                cancelButton()
            }
        }
    }

    private fun upCover() {
        viewModel.book?.let {
            binding.ivCover.setCoverStyle(CoverImageView.CoverStyle.DETAIL)
            binding.ivCover.load(it, false)
        }
    }

    private fun saveData() = binding.run {
        val book = viewModel.book ?: return@run
        val oldBook = book.copy()
        book.name = tieBookName.text?.toString() ?: ""
        book.author = tieBookAuthor.text?.toString() ?: ""
        val local = if (book.isLocal) BookType.local else 0
        val bookType = when (spType.selectedItemPosition) {
            4 -> BookType.video or local
            2 -> BookType.image or local
            1 -> BookType.audio or local
            else -> BookType.text or local
        }
        book.removeType(BookType.video, BookType.local, BookType.image, BookType.audio, BookType.text)
        book.addType(bookType)
        val customCoverUrl = tieCoverUrl.text?.toString()
        book.customCoverUrl = if (customCoverUrl == book.coverUrl) null else customCoverUrl
        val customIntro = tieBookIntro.text?.toString()
        book.customIntro = if (customIntro == book.intro) null else customIntro
        BookHelp.updateCacheFolder(oldBook, book)
        viewModel.saveBook(book) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.book?.customCoverUrl = coverUrl
        binding.tieCoverUrl.setText(coverUrl)
        upCover()
    }

    private fun coverChangeTo(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverChangeTo(uri.toString())
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            runCatching {
                inputStream.use {
                    var file = this.externalFiles
                    val suffix = if (fileDoc.name.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        "." + fileDoc.name.substringAfterLast(".")
                    }
                    val fileName = uri.inputStream(this).getOrThrow().use {
                        MD5Utils.md5Encode(it) + suffix
                    }
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    coverChangeTo(file.absolutePath)
                }
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
