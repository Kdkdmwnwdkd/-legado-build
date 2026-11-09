package com.shimmer.reader.ui.association

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.os.postDelayed
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.base.VMBaseActivity
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.databinding.ActivityTranslucenceBinding
import com.shimmer.reader.exception.InvalidBooksDirException
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.config.BubblePackageManager
import com.shimmer.reader.lib.dialogs.alert
import com.shimmer.reader.lib.permission.Permissions
import com.shimmer.reader.lib.permission.PermissionsCompat
import com.shimmer.reader.ui.file.HandleFileContract
import com.shimmer.reader.ui.book.read.config.HighlightRuleManageActivity
import com.shimmer.reader.utils.FileUtils
import com.shimmer.reader.utils.buildMainHandler
import com.shimmer.reader.utils.canRead
import com.shimmer.reader.utils.checkWrite
import com.shimmer.reader.utils.externalFiles
import com.shimmer.reader.utils.getFile
import com.shimmer.reader.utils.inputStream
import com.shimmer.reader.utils.isContentScheme
import com.shimmer.reader.utils.readUri
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.startActivity
import com.shimmer.reader.utils.startActivityForBook
import com.shimmer.reader.utils.toastOnUi
import com.shimmer.reader.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class FileAssociationActivity :
    VMBaseActivity<ActivityTranslucenceBinding, FileAssociationViewModel>() {

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        currentImportUri?.let { uri ->
            it.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                importBook(treeUri, uri)
            } ?: let {
                val storageHelp = String(assets.open("storageHelp.md").readBytes())
                toastOnUi(storageHelp)
                importBook(null, uri)
            }
        }
    }

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)

    override val viewModel by viewModels<FileAssociationViewModel>()

    private val handler by lazy {
        buildMainHandler()
    }
    private var currentImportUri: Uri? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.rotateLoading.visible()
        viewModel.importBookLiveData.observe(this) { uri ->
            importBook(uri)
        }
        viewModel.importRedThemeLiveData.observe(this) { uri ->
            binding.rotateLoading.gone()
            showDialogFragment(ImportRedThemeDialog(uri, true))
        }
        viewModel.importHighlightLiveData.observe(this) { uri ->
            startActivity<HighlightRuleManageActivity> { data = uri }
            finish()
        }
        viewModel.importBubbleLiveData.observe(this) { uri ->
            importBubble(uri)
        }
        viewModel.onLineImportLive.observe(this) {
            startActivity<OnLineImportActivity> {
                data = it
            }
            finish()
        }
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(ImportBookSourceDialog(it.second, true))
                "rssSource" -> showDialogFragment(ImportRssSourceDialog(it.second, true))
                "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(it.second, true))
                "httpTts" -> showDialogFragment(ImportHttpTtsDialog(it.second, true))
                "theme" -> showDialogFragment(ImportThemeDialog(it.second, true))
                "bubble" -> importBubble(Uri.parse(it.second))
                "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(it.second, true))
                "dictRule" -> showDialogFragment(ImportDictRuleDialog(it.second, true))
            }
        }
        viewModel.errorLive.observe(this) {
            binding.rotateLoading.gone()
            toastOnUi(it)
            handler.postDelayed(2000) {
                finish()
            }
        }
        viewModel.openBookLiveData.observe(this) {
            binding.rotateLoading.gone()
            startActivityForBook(it)
            finish()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            binding.rotateLoading.gone()
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
            ) {
                yesButton {
                    importBook(data.first)
                }
                noButton {
                    finish()
                }
                onCancelled {
                    finish()
                }
            }
        }
        resolveImportUri()?.let { data ->
            currentImportUri = data
            if (data.isContentScheme() && data.canRead()) {
                viewModel.dispatchIntent(data)
            } else {
                PermissionsCompat.Builder()
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted {
                        viewModel.dispatchIntent(data)
                    }.onDenied {
                        toastOnUi(R.string.toast_storage_permission_failed)
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }.request()
            }
        } ?: finish()
    }

    private fun resolveImportUri(): Uri? {
        return intent.data
            ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun importBubble(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    val file = externalFiles.getFile(
                        "bubbleImports",
                        "import_${System.currentTimeMillis()}.zip"
                    )
                    file.parentFile?.mkdirs()
                    uri.inputStream(this@FileAssociationActivity).getOrThrow().use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    BubblePackageManager.importZip(file)
                }
            }.onSuccess {
                binding.rotateLoading.gone()
                toastOnUi(R.string.success)
                finish()
            }.onFailure {
                binding.rotateLoading.gone()
                val msg = getString(R.string.import_bubble_failed) + "\n" + it.localizedMessage
                AppLog.put(msg, it)
                toastOnUi(msg)
                handler.postDelayed(2000) {
                    finish()
                }
            }
        }
    }

    private fun importBook(uri: Uri) {
        if (uri.isContentScheme()) {
            if (tryTakePersistableReadPermission(uri)) {
                importBook(null, uri)
                return
            }
            val treeUriStr = AppConfig.defaultBookTreeUri
            if (treeUriStr.isNullOrEmpty()) {
                localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                    mode = HandleFileContract.DIR_SYS
                }
            } else {
                importBook(Uri.parse(treeUriStr), uri)
            }
        } else {
            importBook(null, uri)
        }
    }

    private fun tryTakePersistableReadPermission(uri: Uri): Boolean {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (intent.flags and readFlag == 0) return false
        return runCatching {
            contentResolver.takePersistableUriPermission(uri, readFlag)
            true
        }.getOrDefault(false)
    }

    private fun importBook(treeUri: Uri?, uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri)
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc =
                            DocumentFile.fromTreeUri(this@FileAssociationActivity, treeUri)
                        if (!treeDoc!!.checkWrite()) {
                            throw InvalidBooksDirException(
                                getString(R.string.import_need_book_folder)
                            )
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            var doc = treeDoc.findFile(name)
                            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                                if (doc == null) {
                                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                        ?: throw InvalidBooksDirException(
                                            getString(R.string.import_need_book_folder)
                                        )
                                }
                                contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(doc.uri)
                        }
                    } else {
                        val treeFile = File(treeUri.path ?: treeUri.toString())
                        if (!treeFile.checkWrite()) {
                            throw InvalidBooksDirException(
                                getString(R.string.import_need_book_folder)
                            )
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            val file = treeFile.getFile(name)
                            if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
                                FileOutputStream(file).use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(Uri.fromFile(file))
                        }
                    }
                }
            }.onFailure {
                when (it) {
                    is InvalidBooksDirException -> localBookTreeSelect.launch {
                        title = getString(R.string.select_book_folder)
                        mode = HandleFileContract.DIR_SYS
                    }

                    else -> {
                        val msg = getString(R.string.import_book_failed) + "\n" + it.localizedMessage
                        AppLog.put(msg, it)
                        toastOnUi(msg)
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }
                }
            }
        }
    }

}
