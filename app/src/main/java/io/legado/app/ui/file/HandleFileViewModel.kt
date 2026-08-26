package io.legado.app.ui.file

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.help.DirectLinkUpload
import io.legado.app.utils.*

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

class HandleFileViewModel(application: Application) : BaseViewModel(application) {

    val errorLiveData = MutableLiveData<String>()

    fun upload(
        fileName: String,
        file: Any,
        contentType: String,
        success: (url: String) -> Unit
    ) {
        execute {
            DirectLinkUpload.upLoad(fileName, file, contentType)
        }.onSuccess {
            success.invoke(it)
        }.onError {
            AppLog.put("上传文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }
    }

    fun saveToLocal(
        uri: Uri,
        fileName: String,
        data: Any,
        contentType: String,
        success: (uri: Uri) -> Unit
    ) {
        execute {
            return@execute if (uri.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(context, uri)!!
                doc.findFile(fileName)?.delete()
                val newDoc = doc.createFile(contentType.ifBlank { "application/octet-stream" }, fileName)
                    ?: error("Create file failed")
                runCatching {
                    writeToUri(newDoc.uri, data)
                    verifySavedFile(newDoc.uri, fileName, data, contentType)
                }.onFailure {
                    newDoc.delete()
                    throw it
                }
                newDoc.uri
            } else {
                val file = File(uri.path ?: uri.toString())
                val newFile = FileUtils.createFileWithReplace(File(file, fileName).absolutePath)
                runCatching {
                    writeToFile(newFile, data)
                    verifySavedFile(Uri.fromFile(newFile), fileName, data, contentType)
                }.onFailure {
                    newFile.delete()
                    throw it
                }
                Uri.fromFile(newFile)
            }
        }.onError {
            it.printOnDebug()
            errorLiveData.postValue(it.localizedMessage)
        }.onSuccess {
            success.invoke(it)
        }
    }

    private fun Any.toSaveBytes(): ByteArray {
        return when (this) {
            is ByteArray -> this
            is String -> toByteArray()
            else -> GSON.toJson(this).toByteArray()
        }
    }

    private fun writeToUri(uri: Uri, data: Any) {
        openTruncatingOutputStream(uri).use { output ->
            writeData(output, data)
        }
    }

    private fun writeToFile(file: File, data: Any) {
        file.outputStream().use { output ->
            writeData(output, data)
        }
    }

    private fun writeData(output: OutputStream, data: Any) {
        if (data is File) {
            data.inputStream().use { input ->
                input.copyTo(output)
            }
        } else {
            output.write(data.toSaveBytes())
        }
        output.flush()
    }

    private fun openTruncatingOutputStream(uri: Uri): OutputStream {
        sequenceOf("rwt", "wt", "w").forEach { mode ->
            runCatching {
                context.contentResolver.openOutputStream(uri, mode)
            }.getOrNull()?.let {
                return it
            }
        }
        return context.contentResolver.openOutputStream(uri)
            ?: error("Open export output failed")
    }

    private fun verifySavedFile(uri: Uri, fileName: String, data: Any, contentType: String) {
        if (data is File) {
            val savedLength = if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L
            } else {
                File(uri.path ?: return).length()
            }
            if (savedLength > 0L && savedLength != data.length()) {
                error("Export size mismatch: expected ${data.length()}, actual $savedLength")
            }
        }
        if (isZipExport(fileName, contentType)) {
            openSavedInputStream(uri).use { input ->
                verifyZipReadable(input)
            }
        }
    }

    private fun openSavedInputStream(uri: Uri): InputStream {
        return if (uri.isContentScheme()) {
            context.contentResolver.openInputStream(uri)
        } else {
            File(uri.path ?: uri.toString()).inputStream()
        } ?: error("Open exported file failed")
    }

    private fun isZipExport(fileName: String, contentType: String): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            contentType.contains("zip", ignoreCase = true)
    }

    private fun verifyZipReadable(input: InputStream) {
        var entries = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries++
                while (zip.read(buffer) >= 0) {
                    // Drain each entry so CRC and stream structure are verified.
                }
                zip.closeEntry()
            }
        }
        if (entries == 0) {
            error("Zip validation failed after export")
        }
    }

}
