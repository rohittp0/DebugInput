package com.rohittp.debuginput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The codec read as a parser rather than as a happy path. Every bug in this scheme lives
 * in one place, which is the accepted trade in
 * docs/adr/0008-length-prefixed-self-describing-encoding.md and only acceptable with
 * this file next to it.
 */
class CodecMalformedTest {

    @Test
    fun `every malformed override decodes to null under every spec`() {
        for (encoded in MALFORMED_ENCODINGS) {
            for (literal in TypeSpecTest.ALL_SPECS) {
                assertNull(
                    decodeValue(encoded, spec(literal)),
                    "'$encoded' decoded as '$literal'",
                )
            }
        }
    }

    @Test
    fun `every malformed override decodes to null without being told a type`() {
        for (encoded in MALFORMED_ENCODINGS) {
            assertNull(decodeAny(encoded), "'$encoded'")
        }
    }

    @Test
    fun `every malformed override decodes to null through every scalar fast path`() {
        for (encoded in MALFORMED_ENCODINGS) {
            for (literal in TypeSpecTest.SCALAR_TAGS) {
                assertNull(fastPath(literal, encoded), "'$encoded' through the $literal fast path")
            }
        }
    }

    @Test
    fun `a payload nested deeper than one level decodes to null rather than running away`() {
        for (levels in listOf(2, 3, 10, 200)) {
            val encoded = deeplyNestedEncoding(levels)
            assertNull(decodeValue(encoded, spec("lst<int>")), "$levels levels")
            assertNull(decodeAny(encoded), "$levels levels")
        }
        // One level is the level that works.
        assertEquals(listOf(1), decodeValue(deeplyNestedEncoding(1), spec("lst<int>")))
    }

    @Test
    fun `a stored override of another type is ignored rather than misread as an Int`() {
        for (encoded in OTHER_TYPE_TAGS) {
            assertNull(decodeValue(encoded, spec("int")), "'$encoded' read as an Int")
            assertNull(decodeInt(encoded), "'$encoded' read as an Int")
        }
    }

    @Test
    fun `a stored override of another type still decodes as its own type`() {
        // Self-describing, so a dormant override is readable — just not as the type that
        // asked for it. That is what lets it apply again if its own type reclaims the id.
        for (encoded in OTHER_TYPE_TAGS) {
            assertNotNull(decodeAny(encoded), "'$encoded'")
        }
        assertEquals(true, decodeAny("bln:4:true"))
        assertEquals(2.5, decodeAny("dbl:3:2.5"))
        assertEquals(listOf(1), decodeAny("lst:7:int:1:1"))
    }

    @Test
    fun `the scalar fast paths and the general parser agree on every valid value`() {
        for ((literal, value) in VALID_SCALARS) {
            val encoded = encoded(value, literal)
            assertEquals(
                decodeValue(encoded, spec(literal)),
                fastPath(literal, encoded),
                "'$encoded' as $literal",
            )
        }
    }

    @Test
    fun `the scalar fast paths refuse a value tagged as another type`() {
        for ((literal, value) in VALID_SCALARS) {
            val encoded = encoded(value, literal)
            for (other in TypeSpecTest.SCALAR_TAGS) {
                if (other == literal) continue
                assertNull(fastPath(other, encoded), "'$encoded' through the $other fast path")
            }
        }
    }

    @Test
    fun `a length that claims more than the payload holds is refused even when it parses`() {
        // "int:3:25" would be a fine Int if the length were read as advice.
        assertNull(decodeValue("int:3:25", spec("int")))
        assertNull(decodeInt("int:3:25"))
        assertEquals(25, decodeInt("int:2:25"))
    }

    @Test
    fun `a tag that is a prefix of another tag is not mistaken for it`() {
        assertNull(decodeInt(encoded(intArrayOf(1), "iarr")), "iarr is not int")
        assertNull(decodeValue(encoded(intArrayOf(1), "iarr"), spec("int")))
        assertNull(decodeString(encoded(3.toShort(), "sht")), "sht is not str")
        assertNull(decodeShort(encoded("3", "str")), "str is not sht")
        assertEquals(1, decodeValue("iarr:7:int:1:1", spec("iarr"))?.let { (it as IntArray)[0] })
    }

    private fun fastPath(literal: String, encoded: String): Any? = when (literal) {
        TAG_INT -> decodeInt(encoded)
        TAG_LONG -> decodeLong(encoded)
        TAG_SHORT -> decodeShort(encoded)
        TAG_BYTE -> decodeByte(encoded)
        TAG_FLOAT -> decodeFloat(encoded)
        TAG_DOUBLE -> decodeDouble(encoded)
        TAG_BOOLEAN -> decodeBoolean(encoded)
        TAG_CHAR -> decodeChar(encoded)
        TAG_STRING -> decodeString(encoded)
        TAG_ENUM -> decodeEnumName(encoded)
        else -> null
    }

    private companion object {
        /** One representative value per scalar tag, including the awkward ones. */
        val VALID_SCALARS: List<Pair<String, Any>> = listOf(
            TAG_INT to 25,
            TAG_INT to Int.MIN_VALUE,
            TAG_LONG to Long.MAX_VALUE,
            TAG_SHORT to (-3).toShort(),
            TAG_BYTE to 7.toByte(),
            TAG_FLOAT to 1.5f,
            TAG_FLOAT to Float.NaN,
            TAG_FLOAT to Float.NEGATIVE_INFINITY,
            TAG_DOUBLE to -0.0,
            TAG_DOUBLE to Double.NaN,
            TAG_BOOLEAN to false,
            TAG_CHAR to ':',
            TAG_STRING to "",
            TAG_STRING to "a:b:c",
            TAG_ENUM to Tier.PRO,
        )
    }
}
