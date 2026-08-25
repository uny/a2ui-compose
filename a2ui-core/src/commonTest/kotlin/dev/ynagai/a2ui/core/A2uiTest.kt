package dev.ynagai.a2ui.core

import kotlin.test.Test
import kotlin.test.assertEquals

class A2uiTest {
    @Test
    fun `protocol version is v1_0`() {
        assertEquals("v1.0", A2ui.PROTOCOL_VERSION)
    }
}
