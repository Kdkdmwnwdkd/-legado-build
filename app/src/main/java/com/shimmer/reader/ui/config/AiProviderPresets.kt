package com.shimmer.reader.ui.config

/**
 * 常用 OpenAI 兼容服务商预设。
 *
 * 新建服务商时先选一项，名称、接口地址和常用模型会一起填好，
 * 用户只需要补一个 API Key，避免面对空白表单不知道填什么。
 */
data class AiProviderPreset(
    val name: String,
    val baseUrl: String,
    val modelId: String = ""
)

object AiProviderPresets {

    val all: List<AiProviderPreset> = listOf(
        AiProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            modelId = "deepseek-chat"
        ),
        AiProviderPreset(
            name = "月之暗面 Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            modelId = "moonshot-v1-8k"
        ),
        AiProviderPreset(
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelId = "glm-4-flash"
        ),
        AiProviderPreset(
            name = "阿里云百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            modelId = "qwen-plus"
        ),
        AiProviderPreset(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "Qwen/Qwen2.5-7B-Instruct"
        ),
        AiProviderPreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-4o-mini"
        ),
        AiProviderPreset(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            modelId = "openai/gpt-4o-mini"
        ),
        AiProviderPreset(
            name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            modelId = "gemini-2.0-flash"
        ),
        AiProviderPreset(
            name = "小米 MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            modelId = "mimo-v2.5-pro"
        ),
        AiProviderPreset(
            name = "商汤 SenseNova",
            baseUrl = "https://token.sensenova.cn/v1",
            modelId = "sensenova-6.7-flash-lite"
        ),
        AiProviderPreset(
            name = "火山引擎",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
    )
}
