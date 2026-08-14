package com.rohittp.debuginput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every tag, out and back. See docs/adr/0008-length-prefixed-self-describing-encoding.md.
 */
class CodecRoundTripTest {

    @Test
    fun `the framing is tag then payload length then payload`() {
        assertEquals("int:2:25", encodeValue(25, spec("int")))
        assertEquals("str:0:", encodeValue("", spec("str")))
        assertEquals("bln:4:true", encodeValue(true, spec("bln")))
        assertEquals("lst:0:", encodeValue(emptyList<String>(), spec("lst<str>")))
    }

    @Test
    fun `a Pair encodes exactly as the ADR says`() {
        assertEquals(
            "pair:20:int:1:3str:7:backoff",
            encodeValue(Pair(3, "backoff"), spec("pair<int,str>")),
        )
    }

    @Test
    fun `a List encodes exactly as the ADR says`() {
        assertEquals(
            "lst:44:str:15:api.example.comstr:15:cdn.example.com",
            encodeValue(listOf("api.example.com", "cdn.example.com"), spec("lst<str>")),
        )
    }

    @Test
    fun `the payload length counts the payload of a container too`() {
        val encoded = assertNotNull(encodeValue(listOf(1, 2, 3), spec("lst<int>")))
        val payload = encoded.substringAfter("lst:").substringAfter(':')
        assertEquals("lst:${payload.length}:$payload", encoded)
        assertEquals("int:1:1int:1:2int:1:3", payload)
    }

    @Test
    fun `an integral override round trips at its extremes`() {
        for (value in listOf(0, 1, -1, 25, -25, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertRoundTrip(value, "int")
        }
        for (value in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertRoundTrip(value, "lng")
        }
        for (value in listOf<Short>(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)) {
            assertRoundTrip(value, "sht")
        }
        for (value in listOf<Byte>(0, 1, -1, Byte.MAX_VALUE, Byte.MIN_VALUE)) {
            assertRoundTrip(value, "byt")
        }
    }

    @Test
    fun `a floating point override round trips including its specials`() {
        for (value in listOf(
            0.0f, -0.0f, 1.5f, -1.5f, Float.MIN_VALUE, Float.MAX_VALUE,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )) {
            assertRoundTrip(value, "flt")
        }
        for (value in listOf(
            0.0, -0.0, 1.5, -1.5, Double.MIN_VALUE, Double.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        )) {
            assertRoundTrip(value, "dbl")
        }
    }

    @Test
    fun `the floating point specials are stored under fixed names on every platform`() {
        assertEquals("flt:3:NaN", encodeValue(Float.NaN, spec("flt")))
        assertEquals("flt:8:Infinity", encodeValue(Float.POSITIVE_INFINITY, spec("flt")))
        assertEquals("flt:9:-Infinity", encodeValue(Float.NEGATIVE_INFINITY, spec("flt")))
        assertEquals("dbl:3:NaN", encodeValue(Double.NaN, spec("dbl")))
        assertEquals("dbl:8:Infinity", encodeValue(Double.POSITIVE_INFINITY, spec("dbl")))
        assertEquals("dbl:9:-Infinity", encodeValue(Double.NEGATIVE_INFINITY, spec("dbl")))
    }

    @Test
    fun `negative zero survives the round trip as itself`() {
        assertEquals(
            (-0.0f).toRawBits(),
            (decodeValue("flt:4:-0.0", spec("flt")) as Float).toRawBits(),
        )
        assertEquals(
            (-0.0).toRawBits(),
            (decodeValue("dbl:4:-0.0", spec("dbl")) as Double).toRawBits(),
        )
    }

    @Test
    fun `a Boolean and a Char override round trip`() {
        assertRoundTrip(true, "bln")
        assertRoundTrip(false, "bln")
        for (value in listOf('a', 'Z', '0', ':', '<', '\n', '\t', ' ', '\u0000', 'e', '\u4e2d')) {
            assertRoundTrip(value, "chr")
        }
    }

    @Test
    fun `each half of a surrogate pair round trips as a Char`() {
        // A Kotlin Char is one UTF-16 unit, so an emoji is two of them and only the
        // halves can be a Char at all.
        val emoji = "\uD83D\uDE00"
        assertRoundTrip(emoji[0], "chr")
        assertRoundTrip(emoji[1], "chr")
        assertEquals("chr:1:${emoji[0]}", encodeValue(emoji[0], spec("chr")))
    }

    @Test
    fun `a String override round trips including the separator and surrogate pairs`() {
        for (value in listOf(
            "",
            " ",
            "backoff",
            ":",
            "::",
            "a:b:c",
            "int:2:25",
            "lst:44:str:15:api.example.com",
            "line\nbreak",
            "\uD83D\uDE00 emoji \uD83D\uDE00",
            "tab\tand\u0000nul",
            "x".repeat(1000),
        )) {
            assertRoundTrip(value, "str")
        }
    }

    @Test
    fun `a String override that looks like a frame is still just a String`() {
        val encoded = assertNotNull(encodeValue("int:2:25", spec("str")))
        assertEquals("str:8:int:2:25", encoded)
        assertEquals("int:2:25", decodeValue(encoded, spec("str")))
        assertNull(decodeValue(encoded, spec("int")), "the outer frame decides the type")
    }

    @Test
    fun `an enum override round trips as its constant name`() {
        assertEquals("enm:4:FREE", encodeValue(Tier.FREE, spec("enm")))
        assertEquals("FREE", decodeValue("enm:4:FREE", spec("enm")))
        assertEquals("enm:4:TEAM", encodeValue("TEAM", spec("enm")), "the page may pass a name")
    }

    @Test
    fun `an empty container round trips`() {
        assertRoundTrip(emptyList<String>(), "lst<str>")
        assertRoundTrip(emptySet<Int>(), "set<int>")
        assertRoundTrip(emptyArray<String>(), "arr<str>")
        assertRoundTrip(intArrayOf(), "iarr")
        assertRoundTrip(doubleArrayOf(), "darr")
        assertRoundTrip(charArrayOf(), "carr")
    }

    @Test
    fun `a single element container round trips`() {
        assertRoundTrip(listOf("only"), "lst<str>")
        assertRoundTrip(setOf(7), "set<int>")
        assertRoundTrip(arrayOf("only"), "arr<str>")
        assertRoundTrip(intArrayOf(7), "iarr")
        assertRoundTrip(booleanArrayOf(true), "zarr")
    }

    @Test
    fun `a collection override round trips`() {
        assertRoundTrip(listOf("api.example.com", "cdn.example.com"), "lst<str>")
        assertRoundTrip(listOf(1, 2, 3, -4, Int.MIN_VALUE), "lst<int>")
        assertRoundTrip(listOf(true, false, true), "lst<bln>")
        assertRoundTrip(setOf(1, 2, 3), "set<int>")
        assertRoundTrip(setOf("a", "b"), "set<str>")
    }

    @Test
    fun `elements containing the separator round trip because the length says where they end`() {
        assertRoundTrip(listOf("a:1", "str:3:xyz", ":", ""), "lst<str>")
        assertRoundTrip(arrayOf("lst:0:", "int:1:1"), "arr<str>")
        assertRoundTrip(Pair(1, "int:1:2"), "pair<int,str>")
        assertRoundTrip(charArrayOf(':', ':', 'a'), "carr")
    }

    @Test
    fun `an object array override round trips`() {
        assertRoundTrip(arrayOf("one", "two"), "arr<str>")
        assertRoundTrip(arrayOf(1, 2, 3), "arr<int>")
        assertRoundTrip(arrayOf(true), "arr<bln>")
    }

    @Test
    fun `an object array decodes to an array of the element type in the spec`() {
        // Generated code casts the result, so an Array of Any would fail on the JVM —
        // including for an empty array, where there is no element to infer a type from.
        val strings = decodeValue(encoded(arrayOf("one"), "arr<str>"), spec("arr<str>"))
        @Suppress("UNCHECKED_CAST")
        val typed = strings as Array<String>
        assertEquals("one", typed[0])

        val empty = decodeValue(encoded(emptyArray<String>(), "arr<str>"), spec("arr<str>"))
        @Suppress("UNCHECKED_CAST")
        val typedEmpty = empty as Array<String>
        assertTrue(typedEmpty.isEmpty())

        val ints = decodeValue(encoded(arrayOf(1), "arr<int>"), spec("arr<int>"))
        @Suppress("UNCHECKED_CAST")
        val typedInts = ints as Array<Int>
        assertEquals(1, typedInts[0])
    }

    @Test
    fun `every primitive array override round trips`() {
        assertRoundTrip(intArrayOf(1, -2, Int.MAX_VALUE, Int.MIN_VALUE), "iarr")
        assertRoundTrip(longArrayOf(1L, Long.MIN_VALUE, Long.MAX_VALUE), "larr")
        assertRoundTrip(shortArrayOf(1, Short.MIN_VALUE, Short.MAX_VALUE), "sarr")
        assertRoundTrip(byteArrayOf(1, Byte.MIN_VALUE, Byte.MAX_VALUE), "barr")
        assertRoundTrip(floatArrayOf(1.5f, -0.0f, Float.NaN, Float.POSITIVE_INFINITY), "farr")
        assertRoundTrip(doubleArrayOf(1.5, -0.0, Double.NaN, Double.NEGATIVE_INFINITY), "darr")
        assertRoundTrip(booleanArrayOf(true, false, true), "zarr")
        assertRoundTrip(charArrayOf('a', ':', '\n'), "carr")
    }

    @Test
    fun `a tuple override round trips`() {
        assertRoundTrip(Pair(3, "backoff"), "pair<int,str>")
        assertRoundTrip(Pair(0, ""), "pair<int,str>")
        assertRoundTrip(Pair("", ""), "pair<str,str>")
        assertRoundTrip(Triple(1, 2, true), "trip<int,int,bln>")
        assertRoundTrip(Triple(0.5, 'x', "s"), "trip<dbl,chr,str>")
    }

    @Test
    fun `a value of the wrong shape for the spec does not encode`() {
        assertNull(encodeValue("25", spec("int")))
        assertNull(encodeValue(25, spec("str")))
        assertNull(encodeValue(25L, spec("int")))
        assertNull(encodeValue(null, spec("int")))
        assertNull(encodeValue(listOf(1), spec("lst<str>")))
        assertNull(encodeValue(setOf(1), spec("lst<int>")))
        assertNull(encodeValue(listOf(1), spec("set<int>")))
        assertNull(encodeValue(intArrayOf(1), spec("larr")))
        assertNull(encodeValue(arrayOf("a"), spec("iarr")))
        assertNull(encodeValue(Pair(1, 2), spec("pair<int,str>")))
        assertNull(encodeValue(Triple(1, 2, 3), spec("pair<int,int>")))
    }

    @Test
    fun `a stored override of another shape decodes to null so the default stands`() {
        val list = encoded(listOf(1, 2), "lst<int>")
        assertNull(decodeValue(list, spec("lst<str>")), "wrong element type")
        assertNull(decodeValue(list, spec("set<int>")), "wrong container")
        assertNull(decodeValue(list, spec("iarr")), "wrong container")
        assertNull(decodeValue(list, spec("int")), "not a scalar")
        assertNull(decodeValue(encoded(25, "int"), spec("lng")), "close but not an Int")
        assertNull(decodeValue(encoded(25, "int"), spec("lst<int>")))
        assertNull(decodeValue(encoded(Pair(1, 2), "pair<int,int>"), spec("trip<int,int,int>")))
        assertNull(decodeValue(encoded(intArrayOf(1), "iarr"), spec("larr")))
    }

    @Test
    fun `decodeAny reads a value the page can show without being told the type`() {
        assertEquals(25, decodeAny(encoded(25, "int")))
        assertEquals(25L, decodeAny(encoded(25L, "lng")))
        assertEquals("a:b", decodeAny(encoded("a:b", "str")))
        assertEquals(true, decodeAny(encoded(true, "bln")))
        assertEquals('x', decodeAny(encoded('x', "chr")))
        assertEquals("FREE", decodeAny(encoded(Tier.FREE, "enm")))
        assertEquals(listOf(1, 2), decodeAny(encoded(listOf(1, 2), "lst<int>")))
        assertEquals(setOf("a"), decodeAny(encoded(setOf("a"), "set<str>")))
        assertEquals(Pair(1, "a"), decodeAny(encoded(Pair(1, "a"), "pair<int,str>")))
        assertEquals(Triple(1, 2, true), decodeAny(encoded(Triple(1, 2, true), "trip<int,int,bln>")))
        assertTrue(
            intArrayOf(1, 2) contentEquals decodeAny(encoded(intArrayOf(1, 2), "iarr")) as IntArray,
        )
    }

    private fun assertRoundTrip(value: Any?, literal: String) {
        val parsed = spec(literal)
        val encoded = assertNotNull(encodeValue(value, parsed), "$value did not encode as $literal")
        val decoded = decodeValue(encoded, parsed)
        assertTrue(
            sameValue(value, decoded),
            "round trip of $value as '$literal' gave $decoded through '$encoded'",
        )
    }
}

/** Equality that treats arrays by content and floating point by bits. */
internal fun sameValue(expected: Any?, actual: Any?): Boolean = when (expected) {
    is FloatArray -> actual is FloatArray && expected.size == actual.size &&
        expected.indices.all { expected[it].toRawBits() == actual[it].toRawBits() }

    is DoubleArray -> actual is DoubleArray && expected.size == actual.size &&
        expected.indices.all { expected[it].toRawBits() == actual[it].toRawBits() }

    is IntArray -> actual is IntArray && expected contentEquals actual
    is LongArray -> actual is LongArray && expected contentEquals actual
    is ShortArray -> actual is ShortArray && expected contentEquals actual
    is ByteArray -> actual is ByteArray && expected contentEquals actual
    is BooleanArray -> actual is BooleanArray && expected contentEquals actual
    is CharArray -> actual is CharArray && expected contentEquals actual
    is Array<*> -> actual is Array<*> && expected contentEquals actual
    else -> expected == actual
}
