package com.anastasiyaa.smartreminder

enum class MaxTokens(val value: Int?) {
    None(null),
    Tiny(8),
    Small(64),
    Medium(256),
    Large(512),
    Huge(1024),
}

enum class Temperature(val value: Double?) {
    None(null),
    Zero(0.0),
    Low(0.5),
    Medium(1.0),
    High(1.5),
    Max(2.0),
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
