package com.shimmer.reader.ui.file

import android.app.Application
import androidx.lifecycle.MutableLiveData
import com.shimmer.reader.base.BaseViewModel
import com.shimmer.reader.utils.toastOnUi
import java.io.File

class FileManageViewModel(application: Application) : BaseViewModel(application) {

    var rootDoc: File? = context.getExternalFilesDir(null)?.parentFile
        private set
    var subDocs = mutableListOf<File>()
    val filesLiveData = MutableLiveData<List<File>>()

    val lastDir: File? get() = subDocs.lastOrNull() ?: rootDoc

    fun setRoot(file: File?) {
        rootDoc = file ?: context.getExternalFilesDir(null)?.parentFile
        subDocs.clear()
    }

    fun upFiles(parentFile: File?) {
        execute {
            parentFile ?: return@execute emptyList()
            parentFile.listFiles()
                ?.filterNot { it.isHiddenWorkspaceBackupDir() }
                ?.sortedWith(compareBy({ it.isFile }, { it.name }))
        }.onStart {
            filesLiveData.postValue(emptyList())
        }.onSuccess {
            filesLiveData.postValue(it ?: emptyList())
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

    fun delFile(file: File) {
        execute {
            file.delete()
        }.onSuccess {
            upFiles(lastDir)
        }.onError {
            context.toastOnUi(it.localizedMessage)
        }
    }

}

private fun File.isHiddenWorkspaceBackupDir(): Boolean {
    return isDirectory && name.lowercase() in setOf(".backup", ".backups")
}
