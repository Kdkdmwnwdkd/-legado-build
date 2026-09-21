package io.legado.app.help.config

internal object EpubReadEnginePolicy {

    const val CORE = "core"
    const val TEXT = "text"
    const val DEFAULT = CORE

    fun normalize(value: String?): String {
        return if (value == TEXT) TEXT else CORE
    }

    fun usesCore(value: String?): Boolean {
        return normalize(value) == CORE
    }
}
