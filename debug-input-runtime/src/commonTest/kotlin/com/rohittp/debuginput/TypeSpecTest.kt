package com.rohittp.debuginput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec literals are baked in at the call site by the compiler plugin, so a well-formed
 * one must parse exactly and anything else must be refused rather than half-understood.
 * A refused spec makes the read resolve its default.
 */
class TypeSpecTest {

    @Test
    fun `every scalar tag parses as a tag with no arguments`() {
        for (tag in SCALAR_TAGS) {
            val parsed = parseTypeSpec(tag)
            assertEquals(tag, parsed?.tag, "spec '$tag'")
            assertTrue(parsed?.arguments?.isEmpty() == true, "spec '$tag' took arguments")
        }
    }

    @Test
    fun `every primitive array tag parses as a tag with no arguments`() {
        for (tag in PRIMITIVE_ARRAY_TAGS) {
            val parsed = parseTypeSpec(tag)
            assertEquals(tag, parsed?.tag, "spec '$tag'")
            assertTrue(parsed?.arguments?.isEmpty() == true, "spec '$tag' took arguments")
        }
    }

    @Test
    fun `a collection spec parses its element type`() {
        for (tag in listOf(TAG_LIST, TAG_SET, TAG_ARRAY)) {
            val parsed = parseTypeSpec("$tag<str>")
            assertEquals(tag, parsed?.tag)
            assertEquals(listOf(TAG_STRING), parsed?.arguments?.map { it.tag }, "spec '$tag'")
        }
    }

    @Test
    fun `a tuple spec parses one element type per component`() {
        assertEquals(
            listOf(TAG_INT, TAG_STRING),
            parseTypeSpec("pair<int,str>")?.arguments?.map { it.tag },
        )
        assertEquals(
            listOf(TAG_INT, TAG_INT, TAG_BOOLEAN),
            parseTypeSpec("trip<int,int,bln>")?.arguments?.map { it.tag },
        )
    }

    @Test
    fun `every spec literal the compiler bakes in parses back to itself`() {
        for (literal in BAKED_IN_SPECS) {
            assertEquals(literal, parseTypeSpec(literal)?.toString(), "spec '$literal'")
        }
    }

    @Test
    fun `a spec nested deeper than one level is refused`() {
        for (literal in listOf(
            "lst<lst<int>>",
            "set<lst<int>>",
            "arr<arr<str>>",
            "pair<int,lst<str>>",
            "trip<int,int,pair<int,int>>",
            "lst<lst<lst<int>>>",
        )) {
            assertNull(parseTypeSpec(literal), "spec '$literal'")
        }
    }

    /**
     * A primitive array takes no type arguments, so a nesting guard that asks whether an
     * argument *has* arguments waves `lst<iarr>` through. The value parser then refuses a
     * container frame below the top level, so such an override could be encoded, written to
     * the store, and silently ignored by every read from then on.
     */
    @Test
    fun `a primitive array inside a container is refused even though it takes no arguments`() {
        for (literal in listOf(
            "lst<iarr>",
            "set<darr>",
            "arr<carr>",
            "pair<iarr,int>",
            "trip<int,zarr,int>",
        )) {
            assertNull(parseTypeSpec(literal), "spec '$literal'")
        }
    }

    @Test
    fun `an enum inside a container is refused because its entry table cannot travel`() {
        for (literal in listOf("lst<enm>", "set<enm>", "arr<enm>", "pair<enm,int>")) {
            assertNull(parseTypeSpec(literal), "spec '$literal'")
        }
    }

    @Test
    fun `an enum on its own is a supported input type`() {
        assertEquals(TAG_ENUM, parseTypeSpec("enm")?.tag)
    }

    @Test
    fun `the wrong number of arguments is refused`() {
        for (literal in listOf(
            "lst<int,str>",
            "set<int,int>",
            "pair<int>",
            "pair<int,int,int>",
            "trip<int,int>",
            "trip<int>",
            "int<str>",
            "str<str>",
            "iarr<int>",
            "lst",
            "set",
            "arr",
            "pair",
            "trip",
        )) {
            assertNull(parseTypeSpec(literal), "spec '$literal'")
        }
    }

    @Test
    fun `a malformed or unknown spec literal is refused`() {
        for (literal in listOf(
            "",
            " ",
            "unknown",
            "Int",
            "INT",
            "int ",
            " int",
            "lst<>",
            "lst<int",
            "lst int>",
            "lst<int>>",
            "lst<<int>",
            "lst<str>x",
            "<str>",
            "lst<,>",
            "lst<int,>",
            "lst<,int>",
            "lst< int >",
            "lst<>>",
            ">",
            "<",
            ",",
        )) {
            assertNull(parseTypeSpec(literal), "spec '$literal'")
        }
    }

    internal companion object {
        val SCALAR_TAGS: List<String> = listOf(
            TAG_INT, TAG_LONG, TAG_SHORT, TAG_BYTE, TAG_FLOAT, TAG_DOUBLE,
            TAG_BOOLEAN, TAG_CHAR, TAG_STRING, TAG_ENUM,
        )

        val PRIMITIVE_ARRAY_TAGS: List<String> = listOf(
            TAG_INT_ARRAY, TAG_LONG_ARRAY, TAG_SHORT_ARRAY, TAG_BYTE_ARRAY,
            TAG_FLOAT_ARRAY, TAG_DOUBLE_ARRAY, TAG_BOOLEAN_ARRAY, TAG_CHAR_ARRAY,
        )

        /** The literals the M2 plan lists as baked in by the compiler plugin. */
        val BAKED_IN_SPECS: List<String> = listOf(
            "int", "str", "lst<str>", "set<int>", "arr<str>", "iarr", "darr",
            "pair<int,str>", "trip<int,int,bln>",
        )

        /** Every spec a decoder might be handed, for the malformed-input sweep. */
        val ALL_SPECS: List<String> = SCALAR_TAGS + PRIMITIVE_ARRAY_TAGS + listOf(
            "lst<int>", "lst<str>", "set<int>", "set<str>", "arr<str>", "arr<int>",
            "pair<int,str>", "trip<int,int,bln>",
        )
    }
}
