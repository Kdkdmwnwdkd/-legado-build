package com.shimmer.reader.help.book.highlight

import android.net.Uri
import com.shimmer.reader.R
import com.shimmer.reader.utils.FileDoc
import com.shimmer.reader.utils.openInputStream
import splitties.init.appCtx

object HighlightImport {
    fun isHighlightFile(file: FileDoc): Boolean = file.name.endsWith(".red", true) && runCatching {
        file.openInputStream().getOrThrow().use {
            HighlightPackageParser.resourceType(HighlightPackageParser.readLimited(it,
                HighlightPackageParser.MAX_PACKAGE_BYTES)) == "highlightRule"
        }
    }.getOrDefault(false)

    data class Report(
        var added: Int = 0,
        var duplicates: Int = 0,
        var skippedFiles: Int = 0,
        val messages: MutableList<String> = arrayListOf()
    ) {
        fun summary(): String = buildString {
            append("导入 ").append(added).append(" 条，重复 ").append(duplicates).append(" 条")
            if (skippedFiles > 0) append("，未导入文件 ").append(skippedFiles).append(" 个")
            if (messages.isNotEmpty()) append("\n\n").append(messages.distinct().joinToString("\n"))
        }
    }

    fun importUri(uri: Uri, bookUrl: String?): Report {
        require(uri.scheme == "content" || uri.scheme == "file") { "请选择本地 .red 高亮规则文件" }
        val file = FileDoc.fromUri(uri, false)
        require(file.name.endsWith(".red", true)) { "请选择 .red 高亮规则文件" }
        val report = Report()
        file.openInputStream().getOrThrow().use { input ->
            runCatching {
                val bytes = HighlightPackageParser.readLimited(input, HighlightPackageParser.MAX_PACKAGE_BYTES)
                val pack = HighlightPackageParser.parse(bytes, bookUrl)
                HighlightRules.store.importPackage(pack)
            }.onSuccess { result ->
                report.added += result.added
                report.duplicates += result.duplicates
                if (result.warnings.isNotEmpty()) {
                    // Keep the full per-rule notice on each management row.
                    report.messages.add(file.name + "：" +
                        result.warnings.size + " 条规则有兼容提示，可在管理页查看")
                }
            }.onFailure {
                report.skippedFiles++
                report.messages.add(
                    appCtx.getString(
                        R.string.highlight_file_read_failed,
                        file.name,
                        it.message ?: appCtx.getString(R.string.common_unable_to_read)
                    )
                )
            }
        }
        return report
    }
}
