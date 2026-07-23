package smartagent.investigator

import java.io.InputStream

/**
 * Reads one UTF-8 code point from [stream].
 * Returns -1 on EOF, the raw first byte on invalid/incomplete sequences.
 */
internal fun readUtf8CodePoint(stream: InputStream): Int {
    val b1 = stream.read()
    if (b1 == -1 || b1 < 0x80) return b1
    val (numExtra, mask) = when {
        b1 and 0xE0 == 0xC0 -> 1 to 0x1F  // 2-byte: 110xxxxx
        b1 and 0xF0 == 0xE0 -> 2 to 0x0F  // 3-byte: 1110xxxx
        b1 and 0xF8 == 0xF0 -> 3 to 0x07  // 4-byte: 11110xxx
        else -> return b1                   // continuation byte — pass through
    }
    var cp = b1 and mask
    repeat(numExtra) {
        val b = stream.read()
        if (b == -1 || b and 0xC0 != 0x80) return b1  // broken sequence
        cp = (cp shl 6) or (b and 0x3F)
    }
    return cp
}
