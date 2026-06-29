package smartagent.agent.toolcalling

import org.junit.Test
import kotlin.test.assertEquals

class ToolFailureTypeTest {

    @Test fun `timeout keyword classifies as TIMEOUT`() =
        assertEquals(ToolFailureType.TIMEOUT, ToolFailureType.classify("initialize timed out — is the server running?"))

    @Test fun `timed out phrase classifies as TIMEOUT`() =
        assertEquals(ToolFailureType.TIMEOUT, ToolFailureType.classify("request timed out after 15000ms"))

    @Test fun `deadline classifies as TIMEOUT`() =
        assertEquals(ToolFailureType.TIMEOUT, ToolFailureType.classify("deadline exceeded"))

    @Test fun `connection refused classifies as NETWORK_ERROR`() =
        assertEquals(ToolFailureType.NETWORK_ERROR, ToolFailureType.classify("Failed to connect to /192.168.1.1:8080"))

    @Test fun `unreachable classifies as NETWORK_ERROR`() =
        assertEquals(ToolFailureType.NETWORK_ERROR, ToolFailureType.classify("host unreachable"))

    @Test fun `401 classifies as BLOCKED_CONTENT`() =
        assertEquals(ToolFailureType.BLOCKED_CONTENT, ToolFailureType.classify("HTTP 401 Unauthorized"))

    @Test fun `403 classifies as BLOCKED_CONTENT`() =
        assertEquals(ToolFailureType.BLOCKED_CONTENT, ToolFailureType.classify("403 forbidden"))

    @Test fun `blocked classifies as BLOCKED_CONTENT`() =
        assertEquals(ToolFailureType.BLOCKED_CONTENT, ToolFailureType.classify("access blocked by bot protection"))

    @Test fun `validation error classifies as VALIDATION_ERROR`() =
        assertEquals(ToolFailureType.VALIDATION_ERROR, ToolFailureType.classify("validation failed: unknown field chat_id"))

    @Test fun `unexpected field classifies as VALIDATION_ERROR`() =
        assertEquals(ToolFailureType.VALIDATION_ERROR, ToolFailureType.classify("unexpected parameter: chat_id"))

    @Test fun `unrecognized error classifies as UNKNOWN_ERROR`() =
        assertEquals(ToolFailureType.UNKNOWN_ERROR, ToolFailureType.classify("something went wrong"))

    @Test fun `case insensitive matching`() {
        assertEquals(ToolFailureType.TIMEOUT, ToolFailureType.classify("TIMED OUT"))
        assertEquals(ToolFailureType.BLOCKED_CONTENT, ToolFailureType.classify("FORBIDDEN"))
    }
}
