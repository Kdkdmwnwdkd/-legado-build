package com.shimmer.reader.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.BaseDialogFragment
import com.shimmer.reader.constant.EventBus
import com.shimmer.reader.data.entities.AiGeneratedImage
import com.shimmer.reader.databinding.DialogReadSelectionImageBinding
import com.shimmer.reader.help.ai.AiImageGalleryManager
import com.shimmer.reader.help.ai.AiImagePromptRewriter
import com.shimmer.reader.help.ai.AiImageService
import com.shimmer.reader.help.book.BookHelp
import com.shimmer.reader.help.book.ContentProcessor
import com.shimmer.reader.help.glide.ImageLoader
import com.shimmer.reader.lib.theme.UiCorner
import com.shimmer.reader.lib.theme.primaryColor
import com.shimmer.reader.lib.theme.themeCardColorOrDefault
import com.shimmer.reader.lib.theme.themeMutedColorOrDefault
import com.shimmer.reader.model.ReadBook
import com.shimmer.reader.utils.gone
import com.shimmer.reader.utils.postEvent
import com.shimmer.reader.utils.setLayout
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import com.shimmer.reader.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadSelectionImageDialog() : BaseDialogFragment(R.layout.dialog_read_selection_image) {

    constructor(prompt: String, paragraphIndex: Int, paragraphText: String) : this() {
        arguments = Bundle().apply {
            putString(EXTRA_PROMPT, prompt)
            putInt(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
            putString(EXTRA_PARAGRAPH_TEXT, paragraphText)
        }
    }

    private val binding by viewBinding(DialogReadSelectionImageBinding::bind)
    private var currentImage: AiGeneratedImage? = null
    private var generateJob: Job? = null
    private val prompt: String
        get() = arguments?.getString(EXTRA_PROMPT).orEmpty()
    private val paragraphIndex: Int
        get() = arguments?.getInt(EXTRA_PARAGRAPH_INDEX, -1) ?: -1
    private val paragraphText: String
        get() = arguments?.getString(EXTRA_PARAGRAPH_TEXT).orEmpty()

    override fun onStart() {
        super.onStart()
        setLayout(0.96f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setNavigationOnClickListener {
            dismissAllowingStateLoss()
        }
        val actionBackground = UiCorner.actionSelector(
            requireContext().themeCardColorOrDefault(),
            requireContext().themeMutedColorOrDefault(),
            UiCorner.actionRadius(requireContext())
        )
        listOf(binding.btnOptimizePrompt, binding.btnRegenerate, binding.btnInsert).forEach {
            it.background = actionBackground
        }
        binding.etPrompt.background = UiCorner.panelRounded(
            requireContext(),
            requireContext().themeCardColorOrDefault(),
            UiCorner.panelRadius(requireContext())
        )
        binding.etPrompt.setText(prompt)
        binding.btnOptimizePrompt.setOnClickListener {
            optimizePrompt()
        }
        binding.btnRegenerate.setOnClickListener {
            generateImage()
        }
        binding.btnInsert.setOnClickListener {
            insertCurrentImage()
        }
        generateImage()
    }

    override fun onDestroyView() {
        generateJob?.cancel()
        super.onDestroyView()
    }

    private fun generateImage() {
        val content = binding.etPrompt.text?.toString().orEmpty().trim()
        if (content.isBlank()) {
            showError(getString(R.string.ai_image_no_selection))
            return
        }
        generateJob?.cancel()
        currentImage = null
        setLoading(true)
        generateJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiImageService.generateAndStore(
                        content,
                        metadata = readSelectionMetadata()
                    )
                }
            }.onSuccess { image ->
                currentImage = image
                ImageLoader.load(requireContext(), image.localPath)
                    .error(R.drawable.image_loading_error)
                    .into(binding.photoView)
                binding.tvState.gone()
                binding.photoView.visible()
                setLoading(false)
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_generate_failed))
            }
        }
    }

    private fun optimizePrompt() {
        val content = binding.etPrompt.text?.toString().orEmpty().trim()
        if (content.isBlank()) {
            showError(getString(R.string.ai_image_no_selection))
            return
        }
        binding.btnOptimizePrompt.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiImagePromptRewriter.rewrite(content, paragraphText)
                }
            }.onSuccess { rewritten ->
                binding.etPrompt.setText(rewritten)
                binding.etPrompt.setSelection(binding.etPrompt.text?.length ?: 0)
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_generate_failed))
            }
            binding.btnOptimizePrompt.isEnabled = true
        }
    }

    private fun insertCurrentImage() {
        val image = currentImage ?: return
        binding.btnInsert.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    insertImageToCurrentChapter(image)
                }
            }.onSuccess { inserted ->
                if (inserted) {
                    ReadBook.clearTextChapter()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                    toastOnUi(R.string.ai_image_inserted)
                    dismissAllowingStateLoss()
                } else {
                    showError(getString(R.string.ai_image_insert_failed))
                }
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_insert_failed))
            }
            binding.btnInsert.isEnabled = currentImage != null
        }
    }

    private fun insertImageToCurrentChapter(image: AiGeneratedImage): Boolean {
        val book = ReadBook.book ?: return false
        val chapter = ReadBook.curTextChapter?.chapter ?: return false
        val rawContent = BookHelp.getContent(book, chapter).orEmpty()
        if (rawContent.isBlank()) return false
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val lines = contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)
            .textList
            .toMutableList()
        if (lines.isEmpty()) return false
        val targetIndex = paragraphIndex.takeIf { it in lines.indices }
            ?: findParagraphIndex(lines, paragraphText)
            ?: return false
        val imageTag = """<img src="${AiImageGalleryManager.imageUri(image.id)}">"""
        if (!lines[targetIndex].contains(imageTag)) {
            lines[targetIndex] = lines[targetIndex].trimEnd() + imageTag
            BookHelp.saveText(book, chapter, lines.joinToString("\n"))
        }
        AiImageGalleryManager.setFavorite(image.id, true, null)
        return true
    }

    private fun readSelectionMetadata(): AiImageGalleryManager.ImageMetadata {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter?.chapter
        return AiImageGalleryManager.ImageMetadata(
            bookName = book?.name.orEmpty(),
            bookAuthor = book?.author.orEmpty(),
            chapterIndex = chapter?.index ?: ReadBook.durChapterIndex,
            chapterTitle = chapter?.title.orEmpty(),
            sourceType = AiImageGalleryManager.SOURCE_TYPE_READ_INSERT,
            sourceText = paragraphText
        )
    }

    private fun findParagraphIndex(lines: List<String>, target: String): Int? {
        val normalizedTarget = normalizeParagraph(target)
        if (normalizedTarget.isBlank()) return null
        return lines.indexOfFirst { line ->
            val normalizedLine = normalizeParagraph(line)
            normalizedLine == normalizedTarget ||
                normalizedLine.contains(normalizedTarget) ||
                normalizedTarget.contains(normalizedLine)
        }.takeIf { it >= 0 }
    }

    private fun normalizeParagraph(text: String): String {
        return text.replace(Regex("""\s+"""), "")
            .replace("\u3000", "")
            .trim()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.isVisible = loading
        binding.tvState.gone()
        binding.btnOptimizePrompt.isEnabled = !loading
        binding.btnRegenerate.isEnabled = !loading
        binding.btnInsert.isEnabled = !loading && currentImage != null
        if (loading) {
            binding.photoView.gone()
        }
    }

    private fun showError(message: String) {
        setLoading(false)
        binding.photoView.gone()
        binding.tvState.visible()
        binding.tvState.text = message
    }

    companion object {
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_PARAGRAPH_INDEX = "paragraphIndex"
        private const val EXTRA_PARAGRAPH_TEXT = "paragraphText"
    }
}
