package com.deskforge.app.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RfbTextEncoderTest {
    @Test
    fun `encodes latin unicode and editor separators`() {
        assertArrayEquals(
            intArrayOf('A'.code, 0x01000416, 0x0101f642, 0xff09, 0xff0d, 0xff0d),
            RfbTextEncoder.encode("AЖ🙂\t\r\n\r"),
        )
    }

    @Test
    fun `rejects unsupported controls and malformed surrogates`() {
        assertNull(RfbTextEncoder.encode("before\u0000after"))
        assertNull(RfbTextEncoder.encode("\ud800"))
        assertNull(RfbTextEncoder.encode("\udc00"))
    }

    @Test
    fun `rejects commits above the bounded code point count`() {
        assertNull(RfbTextEncoder.encode("x".repeat(RfbTextEncoder.MAX_COMMITTED_CODE_POINTS + 1)))
    }
}
