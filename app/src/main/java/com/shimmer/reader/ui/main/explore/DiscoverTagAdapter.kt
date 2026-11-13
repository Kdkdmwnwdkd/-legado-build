package com.shimmer.reader.ui.main.explore

import com.shimmer.reader.data.entities.rule.ExploreKind

data class DiscoverTagItem(
    val kind: ExploreKind,
    val text: String,
    val role: Role,
    val group: String? = null,
) {
    enum class Role {
        UrlTag,
        GlobalSelect,
        Toggle,
        ActionButton,
        ScriptUrl
    }

    val isButton: Boolean
        get() = role == Role.ActionButton || role == Role.ScriptUrl || role == Role.Toggle
}
