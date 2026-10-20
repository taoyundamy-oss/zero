package com.zero.v2.services.llm

/** Curated dialects; selecting a contract is an explicit declaration about the deployed model. */
object ModelContracts {
    private fun thinking(vararg path: String) =
        ParameterSpec(
            "thinking",
            "启用思考",
            path.toList(),
            ParameterType.BOOLEAN,
        )

    private fun effort(vararg values: String) =
        ParameterSpec(
            "effort",
            "思考强度",
            listOf("reasoning_effort"),
            ParameterType.ENUM,
            values.toList(),
        )

    private val deepseek = listOf(thinking("thinking", "type"), effort("low", "high", "max"))
    val all: List<ModelContract> =
        listOf(
            ModelContract(
                ChatGptPlanProtocol.CONTRACT,
                "ChatGPT套餐（独立账号）",
                listOf(
                    ParameterSpec(
                        "effort",
                        "思考强度（以账号模型支持为准）",
                        listOf("reasoning", "effort"),
                        ParameterType.ENUM,
                        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")),
                ),
                temperature = false),
            ModelContract("generic-v1", "通用兼容（无思考扩展）"),
            ModelContract(
                "gemini-native-v1",
                "Gemini原生（候选；按型号声明能力）",
                listOf(
                    ParameterSpec(
                        "effort",
                        "思考级别",
                        listOf("generationConfig", "thinkingConfig", "thinkingLevel"),
                        ParameterType.ENUM,
                        listOf("minimal", "low", "medium", "high")),
                    ParameterSpec(
                        "budget",
                        "思考预算（不与级别同时设置）",
                        listOf("generationConfig", "thinkingConfig", "thinkingBudget"),
                        ParameterType.INTEGER,
                        minimum = 0),
                )),
            ModelContract(
                "openai-responses-v1",
                "OpenAI官方 Responses（独立API计费）",
                listOf(
                    ParameterSpec(
                        "effort",
                        "思考强度（以所选型号为准）",
                        listOf("reasoning", "effort"),
                        ParameterType.ENUM,
                        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")),
                ),
                outputField = "max_output_tokens",
                temperature = false),
            ModelContract(
                "zhipu-thinking-v1",
                "智谱思考及跨轮保留（按型号支持）",
                listOf(
                    thinking("thinking", "type"),
                    ParameterSpec(
                        "clear_thinking",
                        "清除历史思考（false保留）",
                        listOf("thinking", "clear_thinking"),
                        ParameterType.BOOLEAN),
                ),
                replayReasoning = true),
            ModelContract(
                "gemini-openai-v1",
                "Gemini兼容接口思考强度（非原生全功能）",
                listOf(
                    effort("minimal", "low", "medium", "high"),
                )),
            ModelContract(
                "cf-openai-responses-v1",
                "CF OpenAI Responses（按模型选参数）",
                listOf(
                    ParameterSpec(
                        "effort",
                        "思考强度",
                        listOf("reasoning", "effort"),
                        ParameterType.ENUM,
                        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")),
                ),
                outputField = "max_output_tokens",
                temperature = false),
            ModelContract(
                "cf-luna-responses-v1",
                "CF GPT-6 Luna Responses（思考与工具）",
                listOf(
                    ParameterSpec(
                        "effort",
                        "思考强度",
                        listOf("reasoning", "effort"),
                        ParameterType.ENUM,
                        listOf("none", "low", "medium", "high", "xhigh", "max")),
                ),
                outputField = "max_output_tokens",
                temperature = false),
            ModelContract(
                "cf-anthropic-native-v1",
                "CF Anthropic 原生思考与签名",
                listOf(
                    thinking("thinking", "type"),
                    ParameterSpec(
                        "budget",
                        "思考预算",
                        listOf("thinking", "budget_tokens"),
                        ParameterType.INTEGER,
                        minimum = 1024),
                ),
                temperature = false),
            ModelContract(
                "dashscope-v1",
                "DashScope 混合思考与预算",
                listOf(
                    thinking("enable_thinking"),
                    ParameterSpec(
                        "budget", "思考预算", listOf("thinking_budget"), ParameterType.INTEGER),
                )),
            ModelContract(
                "dashscope-effort-v1",
                "DashScope Qwen 思考强度",
                listOf(
                    thinking("enable_thinking"),
                    effort("low", "medium", "xhigh", "high", "max"),
                )),
            ModelContract(
                "mimo-v1",
                "MiMo 思考（使用服务端温度）",
                listOf(thinking("thinking", "type")),
                temperature = false,
                replayReasoning = true),
            ModelContract("deepseek-v1", "DeepSeek 思考", deepseek, replayReasoning = true),
            ModelContract(
                "deepseek-legacy-v1", "DeepSeek 旧配置（同步沿用默认）", deepseek, replayReasoning = true),
            ModelContract(
                "effort-v1",
                "兼容 reasoning_effort / max_completion_tokens",
                listOf(
                    effort("none", "minimal", "low", "medium", "high", "xhigh"),
                ),
                outputField = "max_completion_tokens",
                temperature = false),
            ModelContract(
                "template-thinking-v1",
                "本地 chat_template_kwargs 思考开关",
                listOf(
                    thinking("chat_template_kwargs", "enable_thinking"),
                )),
            ModelContract(
                "ollama-effort-v1",
                "Ollama reasoning_effort",
                listOf(
                    effort("none", "low", "medium", "high", "max"),
                )),
            ModelContract(
                "cf-qwen38-v1",
                "CF Qwen3.8 27B（low / medium / xhigh）",
                listOf(
                    effort("low", "medium", "xhigh"),
                ),
                outputField = "max_completion_tokens"),
        )

    fun get(id: String): ModelContract = requireNotNull(all.find { it.id == id }) { "未知模型参数契约：$id" }

    fun validationError(profile: LlmProfile): String? =
        runCatching { get(profile.contractId).validate(profile) }.exceptionOrNull()?.message
}
