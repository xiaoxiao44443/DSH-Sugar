package app.dsh.sugar

import org.junit.Assert.*
import org.junit.Test

class AgentNotificationPolicyTest {
    @Test fun existingSettingsKeepTheirMeaningWhenPetMuteIsOff() {
        val policy = AgentNotificationPolicy(muteWhilePetVisible = false)
        assertTrue(policy.allows("completion", false, true))
        assertFalse(policy.allows("completion", true, false))
        assertTrue(policy.allows("permission", true, true))
        assertTrue(policy.allows("question", true, true))
        assertFalse(policy.copy(completion = "off").allows("completion", false, false))
        assertTrue(policy.copy(completion = "always").allows("completion", true, false))
        assertFalse(policy.copy(permission = false).allows("permission", false, false))
        assertFalse(policy.copy(question = false).allows("question", false, false))
    }

    @Test fun onlyAnActuallyVisiblePetSuppressesAllThreeMessageKinds() {
        val policy = AgentNotificationPolicy(completion = "always")
        assertTrue(policy.muteWhilePetVisible)
        for (kind in listOf("completion", "permission", "question")) {
            assertFalse(policy.allows(kind, false, true))
            assertTrue(policy.allows(kind, false, false))
        }
        assertFalse(policy.copy(question = false).allows("question", false, false))
        assertFalse(policy.allows("unknown", false, false))
    }

    @Test fun notificationBodyRetainsParagraphsButBoundsUntrustedInput() {
        assertEquals("第一段\n\n第二段\n- 项目", AgentNotificationBody.normalize(" 第一段\r\n\r\n第二段\n- 项目 "))
        assertNull(AgentNotificationBody.normalize(" \n\t"))
        assertEquals("安全文本", AgentNotificationBody.normalize("\u202e安全\u0000文本\u2069"))
        val long = AgentNotificationBody.normalize("x".repeat(1998) + "🐳结尾")!!
        assertTrue(long.length <= 2000)
        assertEquals("x".repeat(1998) + "…", long)
    }
}
