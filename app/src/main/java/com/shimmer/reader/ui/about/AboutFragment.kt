package com.shimmer.reader.ui.about

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.shimmer.reader.R
import com.shimmer.reader.constant.AppConst.appInfo
import com.shimmer.reader.constant.AppLog
import com.shimmer.reader.help.CrashHandler
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.help.coroutine.Coroutine
import com.shimmer.reader.help.update.AppUpdate
import com.shimmer.reader.help.update.AppUpdateConfig
import com.shimmer.reader.ui.config.compose.ComposeSettingFragment
import com.shimmer.reader.ui.config.compose.SettingActionSpec
import com.shimmer.reader.ui.config.compose.SettingPageSpec
import com.shimmer.reader.ui.config.compose.SettingSectionSpec
import com.shimmer.reader.ui.widget.dialog.TextDialog
import com.shimmer.reader.ui.widget.dialog.WaitDialog
import com.shimmer.reader.ui.widget.compose.showComposeTextFormDialogWithChecks
import com.shimmer.reader.utils.FileDoc
import com.shimmer.reader.utils.compress.ZipUtils
import com.shimmer.reader.utils.createFileIfNotExist
import com.shimmer.reader.utils.createFolderIfNotExist
import com.shimmer.reader.utils.delete
import com.shimmer.reader.utils.externalCache
import com.shimmer.reader.utils.find
import com.shimmer.reader.utils.list
import com.shimmer.reader.utils.openInputStream
import com.shimmer.reader.utils.openOutputStream
import com.shimmer.reader.utils.openUrl
import com.shimmer.reader.utils.showDialogFragment
import com.shimmer.reader.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File

class AboutFragment : ComposeSettingFragment() {

    private val waitDialog by lazy {
        WaitDialog(requireContext())
    }

    override val titleRes: Int = R.string.about

    override val autoOpenTargetItem: Boolean = false

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        action(
                            key = KEY_CONTRIBUTORS,
                            title = getString(R.string.contributors),
                            summary = getString(R.string.contributors_summary_sigma)
                        ) {
                            openUrl(R.string.contributors_url)
                        },
                        action(
                            key = KEY_UPDATE_LOG,
                            title = getString(R.string.update_log),
                            summary = "${getString(R.string.version)} ${appInfo.versionName}"
                        ) {
                            showMdFile(getString(R.string.update_log), "updateLog.md")
                        },
                        action(
                            key = KEY_CHECK_UPDATE,
                            title = getString(R.string.check_update)
                        ) {
                            checkUpdate()
                        },
                        action(
                            key = KEY_INTERNAL_BETA,
                            title = getString(R.string.about_beta_badge),
                            summary = internalBetaSummary()
                        ) {
                            showInternalBetaDialog()
                        },
                        action(
                            key = KEY_UPDATE_ACCELERATION,
                            title = getString(R.string.update_acceleration_manage)
                        ) {
                            showUpdateAccelerationManage()
                        }
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.other),
                    items = listOf(
                        action(
                            key = KEY_CRASH_LOG,
                            title = getString(R.string.crash_log)
                        ) {
                            showDialogFragment<CrashLogsDialog>()
                        },
                        action(
                            key = KEY_SAVE_LOG,
                            title = getString(R.string.save_log)
                        ) {
                            saveLog()
                        },
                        action(
                            key = KEY_CREATE_HEAP_DUMP,
                            title = getString(R.string.create_heap_dump)
                        ) {
                            createHeapDump()
                        },
                        action(
                            key = KEY_PRIVACY_POLICY,
                            title = getString(R.string.privacy_policy)
                        ) {
                            showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
                        },
                        action(
                            key = KEY_LICENSE,
                            title = getString(R.string.license)
                        ) {
                            showMdFile(getString(R.string.license), "LICENSE.md")
                        },
                        action(
                            key = KEY_DISCLAIMER,
                            title = getString(R.string.disclaimer)
                        ) {
                            showMdFile(getString(R.string.disclaimer), "disclaimer.md")
                        }
                    )
                )
            )
        )
    }

    private fun action(
        key: String,
        title: CharSequence,
        summary: CharSequence? = null,
        onClick: () -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            onClick = onClick
        )
    }

    private fun showUpdateAccelerationManage() {
        UpdateAcceleratorDialog.show(this) {
            refreshSettings()
        }
    }

    private fun internalBetaSummary(): String {
        val enabled = AppUpdateConfig.internalBetaConfigured
        val url = AppUpdateConfig.internalBetaUrl.orEmpty()
        return if (enabled) {
            getString(R.string.about_accel_enabled, url)
        } else {
            getString(R.string.about_accel_disabled)
        }
    }

    private fun showInternalBetaDialog() {
        showComposeTextFormDialogWithChecks(
            title = getString(R.string.about_beta_update),
            labels = listOf(
                getString(R.string.about_beta_download_key),
                getString(R.string.about_beta_download_url)
            ),
            initialValues = listOf(
                AppUpdateConfig.internalBetaKey.orEmpty(),
                AppUpdateConfig.internalBetaUrl.orEmpty()
            ),
            passwordFields = setOf(0),
            checkboxLabels = listOf(getString(R.string.about_beta_enable)),
            checkedIndices = if (AppUpdateConfig.internalBetaEnabled) setOf(0) else emptySet(),
            message = getString(R.string.about_beta_enable_message),
            validateInput = { values ->
                val key = values.getOrNull(0).orEmpty().trim()
                val url = values.getOrNull(1).orEmpty().trim()
                val hasAny = key.isNotBlank() || url.isNotBlank()
                !hasAny || (key.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://")))
            },
            onPositive = { values, checked ->
                val key = values.getOrNull(0).orEmpty().trim()
                val url = values.getOrNull(1).orEmpty().trim()
                val valid = key.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))
                AppUpdateConfig.internalBetaKey = key
                AppUpdateConfig.internalBetaUrl = url
                AppUpdateConfig.internalBetaEnabled = checked.getOrNull(0) == true && valid
                refreshSettings()
            }
        )
    }

    @Suppress("SameParameterValue")
    private fun openUrl(@StringRes addressID: Int) {
        requireContext().openUrl(getString(addressID))
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(requireContext().assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    /**
     * 检测更新
     */
    private fun checkUpdate() {
        waitDialog.show()
        AppUpdate.preferredUpdate.run {
            check(lifecycleScope)
                .onSuccess {
                    showDialogFragment(
                        UpdateDialog(it)
                    )
                }.onError {
                    showDialogFragment(
                        TextDialog(
                            getString(R.string.check_update),
                            if (AppUpdate.isLatestVersionError(it)) {
                                getString(R.string.update_no_new_version)
                            } else {
                                it.localizedMessage ?: getString(R.string.check_update)
                            },
                            TextDialog.Mode.TEXT
                        )
                    )
                }.onFinally {
                    waitDialog.dismiss()
                }
        }
    }


    /**
     * 加入qq群
     */
    private fun joinQQGroup(key: String): Boolean {
        val intent = Intent()
        intent.data =
            Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26k%3D$key")
        // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面
        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        kotlin.runCatching {
            startActivity(intent)
            return true
        }.onFailure {
            toastOnUi(R.string.toast_add_failed_manual)
        }
        return false
    }

    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi(R.string.toast_backup_dir_not_set)
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi(R.string.toast_log_disabled)
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi(R.string.toast_saved_to_backup_dir)
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi(R.string.toast_backup_dir_not_set)
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi(R.string.toast_heap_dump_disabled)
                delay(3000)
            }
            appCtx.toastOnUi(R.string.toast_heap_dump_started)
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi(R.string.toast_heap_dump_not_found)
            } else {
                appCtx.toastOnUi(R.string.toast_saved_to_backup_dir)
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }

    companion object {
        private const val KEY_CONTRIBUTORS = "contributors"
        private const val KEY_UPDATE_LOG = "update_log"
        private const val KEY_CHECK_UPDATE = "check_update"
        private const val KEY_INTERNAL_BETA = "internal_beta"
        private const val KEY_UPDATE_ACCELERATION = "update_acceleration_manage"
        private const val KEY_LICENSE = "license"
        private const val KEY_DISCLAIMER = "disclaimer"
        private const val KEY_PRIVACY_POLICY = "privacyPolicy"
        private const val KEY_CRASH_LOG = "crashLog"
        private const val KEY_SAVE_LOG = "saveLog"
        private const val KEY_CREATE_HEAP_DUMP = "createHeapDump"
    }

}
