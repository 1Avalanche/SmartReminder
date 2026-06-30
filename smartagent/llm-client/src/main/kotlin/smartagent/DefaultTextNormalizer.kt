package smartagent

class DefaultTextNormalizer : TextNormalizer {

    private val controlCharsRegex = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F]""")
    private val surrogateRegex = Regex("""[\uD800-\uDFFF]""")
    private val nullByteRegex = Regex("""\x00""")

    override fun normalize(text: String): String = text
        .replace(nullByteRegex, "")
        .replace(controlCharsRegex, "")
        .replace(surrogateRegex, "")
}
