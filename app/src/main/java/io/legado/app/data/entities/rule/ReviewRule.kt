package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReviewRule(
    var reviewUrl: String? = null,          // 段评URL
    var avatarRule: String? = null,         // 段评发布者头像
    var contentRule: String? = null,        // 段评内容
    var postTimeRule: String? = null,       // 段评发布时间
    var reviewQuoteUrl: String? = null,     // 获取段评回复URL

    // 这些功能将在以上功能完成以后实现
    var voteUpUrl: String? = null,          // 点赞URL
    var voteDownUrl: String? = null,        // 点踩URL
    var postReviewUrl: String? = null,      // 发送回复URL
    var postQuoteUrl: String? = null,       // 发送回复段评URL
    var deleteUrl: String? = null,          // 删除段评URL

    // 段评摘要与详情页（与上游保持字段一致，保证书源导入导出无损）
    var enabled: Boolean = false,                 // 是否启用段评
    var reviewSummaryUrl: String? = null,         // 段评摘要URL
    var summaryListRule: String? = null,          // 摘要列表规则
    var summaryParagraphIndexRule: String? = null,// 摘要对应段落序号
    var summaryParagraphDataRule: String? = null, // 摘要段落内数据
    var summaryCountRule: String? = null,         // 摘要数量
    var reviewDetailUrl: String? = null,          // 段评详情页URL
    var reviewDetailNextPageUrl: String? = null,  // 段评详情下一页
    var detailListRule: String? = null,           // 详情列表规则
    var detailIdRule: String? = null,             // 详情ID
    var detailAvatarRule: String? = null,         // 详情头像
    var detailNameRule: String? = null,           // 详情昵称
    var detailBadgeRule: String? = null,          // 详情徽章
    var detailContentRule: String? = null,        // 详情内容
    var replyListRule: String? = null,            // 回复列表规则
    var replyIdRule: String? = null,              // 回复ID
    var replyAvatarRule: String? = null,          // 回复头像
    var replyNameRule: String? = null,            // 回复昵称
    var replyBadgeRule: String? = null,           // 回复徽章
    var replyContentRule: String? = null,         // 回复内容
) : Parcelable {

    /**
     * 段评摘要的规则是否齐全（不看 enabled 开关）。
     * 部分社区书源只写了规则、漏写 enabled，这里单独提供判据，
     * 由用户级开关 [io.legado.app.help.config.AppConfig.enableReview] 决定是否真正启用。
     */
    fun hasSummaryRule(): Boolean {
        return !reviewSummaryUrl.isNullOrBlank() &&
            !summaryListRule.isNullOrBlank() &&
            !summaryParagraphIndexRule.isNullOrBlank() &&
            !summaryCountRule.isNullOrBlank()
    }

    /**
     * 只有同时配置了启用开关、摘要列表、段落序号与数量规则时，摘要链才可用
     */
    fun configuredSummaryUrl(): String? {
        if (!enabled || summaryListRule.isNullOrBlank() ||
            summaryParagraphIndexRule.isNullOrBlank() || summaryCountRule.isNullOrBlank()
        ) {
            return null
        }
        return reviewSummaryUrl?.takeIf { it.isNotBlank() }
    }

    companion object {

        val jsonDeserializer = JsonDeserializer<ReviewRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, ReviewRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(json.asString, ReviewRule::class.java)
                else -> null
            }
        }

    }

}
