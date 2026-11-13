package com.shimmer.reader.data.entities.rule

data class RowUi(
    val name: String = "",
    val type: String = "text",
    val action: String? = null,
    val chars: Array<String?>? = null,
    val default: String? = null,
    var viewName: String? = null,
    val style: FlexChildStyle? = null,
    // ---- 登录界面 v2 字段 ----
    /** 取值用的键名，text/password/select/toggle 必填且需唯一 */
    val key: String? = null,
    /** 输入提示（placeholder） */
    val hint: String? = null,
    /** 渲染时预设的值，优于已保存的登录信息 */
    val value: String? = null,
    /** select 的可选项 */
    val options: List<String>? = null,
    /** button 点击后的冷却秒数 */
    val countdown: Int? = null,
) {

    @Suppress("ConstPropertyName")
    object Type {

        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val label = "label"
        const val toggle = "toggle"
        const val select = "select"

    }

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }

    override fun equals(other: Any?): Boolean {
        if (other is RowUi) {
            return other.name == name
                    && other.type == type
                    && other.action == action
                    && other.default == default
        }
        return false
    }

    override fun hashCode(): Int {
        var result = name.hashCode() + type.hashCode()
        result = 31 * result + (action?.hashCode() ?: 0)
        result = 31 * result + (default?.hashCode() ?: 0)
        return result
    }

}