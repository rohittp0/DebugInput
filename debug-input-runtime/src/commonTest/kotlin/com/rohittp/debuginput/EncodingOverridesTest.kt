package com.rohittp.debuginput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Int fast path, which is the one every M1 override took and the one the hottest
 * reads still take. The wider codec is covered by [CodecRoundTripTest] and
 * [CodecMalformedTest].
 */
class EncodingOverridesTest {

    @Test
    fun `an Int override is encoded with its tag and its payload length`() {
        assertEquals("int:2:25", encodeInt(25))
        assertEquals("int:3:-25", encodeInt(-25))
        assertEquals("int:1:0", encodeInt(0))
        assertEquals("int:10:2147483647", encodeInt(Int.MAX_VALUE))
        assertEquals("int:11:-2147483648", encodeInt(Int.MIN_VALUE))
    }

    @Test
    fun `an encoded Int override decodes back to itself`() {
        for (value in listOf(0, 1, -1, 25, -25, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(value, decodeInt(encodeInt(value)), "round trip of $value")
        }
    }

    @Test
    fun `the Int fast path and the general codec write the same bytes`() {
        for (value in listOf(0, -1, 25, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(encodeInt(value), encodeValue(value, spec("int")), "for $value")
        }
    }

    @Test
    fun `an override tagged as another type decodes to null instead of throwing`() {
        for (encoded in OTHER_TYPE_TAGS) {
            assertNull(decodeInt(encoded), "tagged override $encoded")
        }
    }

    @Test
    fun `a malformed encoded override decodes to null instead of throwing`() {
        for (encoded in MALFORMED_ENCODINGS) {
            assertNull(decodeInt(encoded), "malformed override '$encoded'")
        }
    }

    @Test
    fun `decodeAny reads any tagged value and refuses only what is not one`() {
        // The wire form is self-describing, so the page can read a stored override
        // without being told its type — including one left behind by another type.
        assertEquals(25, decodeAny(encodeInt(25)))
        for (encoded in OTHER_TYPE_TAGS) {
            assertNotNull(decodeAny(encoded), "readable override '$encoded'")
        }
        for (encoded in MALFORMED_ENCODINGS) {
            assertNull(decodeAny(encoded), "undecodable override '$encoded'")
        }
    }
}
