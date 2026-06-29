package smartagent.agent.toolcalling

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class OutputValidatorTest {

    // ─── containsXml ─────────────────────────────────────────────────────────

    @Test fun `tool_calls XML detected`() {
        assertTrue(OutputValidator.containsXml("<tool_calls><tool_call>...</tool_call></tool_calls>"))
    }

    @Test fun `invoke XML detected`() {
        assertTrue(OutputValidator.containsXml("<invoke></invoke>"))
    }

    @Test fun `function_calls XML detected`() {
        assertTrue(OutputValidator.containsXml("<function_calls>blah</function_calls>"))
    }

    @Test fun `XML detection case insensitive`() {
        assertTrue(OutputValidator.containsXml("<TOOL_CALLS>x</TOOL_CALLS>"))
    }

    @Test fun `plain FINAL_ANSWER not detected as XML`() {
        assertFalse(OutputValidator.containsXml("FINAL_ANSWER\nHello user"))
    }

    @Test fun `plain TOOL_CALL not detected as XML`() {
        assertFalse(OutputValidator.containsXml("TOOL_CALL\ntool=tavily_search\narguments={}"))
    }

    @Test fun `empty string not detected as XML`() {
        assertFalse(OutputValidator.containsXml(""))
    }

    // ─── isSafeForUser ───────────────────────────────────────────────────────

    @Test fun `clean text is safe`() {
        assertTrue(OutputValidator.isSafeForUser("Напоминание создано на 14:00"))
    }

    @Test fun `XML in text is unsafe`() {
        assertFalse(OutputValidator.isSafeForUser("Here is my answer <tool_calls></tool_calls>"))
    }

    // ─── xmlRejectionPrompt ───────────────────────────────────────────────────

    @Test fun `xmlRejectionPrompt contains format instructions`() {
        val prompt = OutputValidator.xmlRejectionPrompt("<tool_calls/>")
        assertTrue(prompt.contains("TOOL_CALL"))
        assertTrue(prompt.contains("FINAL_ANSWER"))
        assertTrue(prompt.contains("unsupported XML"))
    }

    // ─── parseErrorRecoveryPrompt ─────────────────────────────────────────────

    @Test fun `parseErrorRecoveryPrompt contains reason and format`() {
        val prompt = OutputValidator.parseErrorRecoveryPrompt("blah", "unrecognized format")
        assertTrue(prompt.contains("unrecognized format"))
        assertTrue(prompt.contains("TOOL_CALL"))
        assertTrue(prompt.contains("FINAL_ANSWER"))
    }

    // ─── constants ────────────────────────────────────────────────────────────

    @Test fun `MAX_PARSE_RETRIES is 2`() {
        assertEquals(2, OutputValidator.MAX_PARSE_RETRIES)
    }

    @Test fun `FALLBACK_MESSAGE is non-empty and user-safe`() {
        assertTrue(OutputValidator.FALLBACK_MESSAGE.isNotBlank())
        assertTrue(OutputValidator.isSafeForUser(OutputValidator.FALLBACK_MESSAGE))
    }
}
