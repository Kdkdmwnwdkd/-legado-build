package com.shimmer.reader.help.config

import java.io.File

internal fun File.writeTextIfChanged(text: String) {
    parentFile?.mkdirs()
    if (exists() && readText() == text) return
    writeText(text)
}
