package smartagent

internal object ApiKeySanitizer {

    // NBSP, soft-hyphen, zero-width space/joiner/non-joiner, word-joiner, BOM
    private val INVISIBLE = Regex("[ ­​‌‍⁠﻿]")

    fun sanitize(raw: String): String {
        val cleaned = raw
            .replace("\r", "")
            .replace("\n", "")
            .replace(INVISIBLE, "")
            .filter { it.code in 33..126 }  // printable ASCII only, no space
            .trim()

        if (cleaned != raw.trim()) {
            println("[ApiKeySanitizer] Warning: API key contained invisible/non-ASCII characters, sanitized")
        }

        require(cleaned.isNotEmpty()) { "Invalid API key after sanitization: result is empty" }

        return cleaned
    }
}
