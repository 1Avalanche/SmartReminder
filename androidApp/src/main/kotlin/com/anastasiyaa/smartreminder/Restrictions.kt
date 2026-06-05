package com.anastasiyaa.smartreminder

enum class MaxTokens(val value: Int?) {
    None(null),
    Tiny(8),
    Small(64),
    Medium(256),
    Large(512),
    Huge(1024),
}

enum class AnswerFormat(val query: String?) {
    None(null),
    JSON("json"),
    RuText("Текст на русском"),
    EnText("Текст на английском"),
}

enum class MaxCharacters(val value: Int?) {
    None(null),
    Tiny(30),
    Small(100),
    Medium(500),
    Large(1000),
    Huge(2000),
}

class StopSequence(val value: String)

enum class ModelFamily {
    DeepSeek, Qwen, Llama
}

enum class Model(val family: ModelFamily, val modelId: String) {
    DeepSeekR1(ModelFamily.DeepSeek, "deepseek/deepseek-r1"),
    DeepSeekChat3_1(ModelFamily.DeepSeek, "deepseek/deepseek-chat-v3.1"),
    DeepSeekV4Pro(ModelFamily.DeepSeek, "deepseek/deepseek-v4-pro"),
    Qwen2_5__7_2b_instruct(ModelFamily.Qwen, "qwen/qwen-2.5-72b-instruct"),
    Qwen3_32B(ModelFamily.Qwen, "qwen/qwen3-32b"),
    Qwen3_235B(ModelFamily.Qwen, "qwen/qwen3-235b-a22b-thinking-2507"),
    Llama3_2_1B(ModelFamily.Llama, "meta-llama/llama-3.2-1b-instruct"),
}
