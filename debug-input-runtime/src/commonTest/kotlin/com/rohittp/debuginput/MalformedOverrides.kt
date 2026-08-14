package com.rohittp.debuginput

/**
 * Stored strings that are not a value at all: truncated payloads, lengths that overrun
 * or undershoot, negative and non-numeric lengths, missing separators, unknown tags,
 * containers that do not parse to a whole number of elements, and payloads nested
 * deeper than one level.
 *
 * Every decoder must answer null for every one of these, for every spec, and none may
 * throw — they are read straight off a preferences file that any past build of the app
 * may have written. See docs/adr/0008-length-prefixed-self-describing-encoding.md.
 */
internal val MALFORMED_ENCODINGS: List<String> = listOf(
    // Nothing, or nothing but separators.
    "",
    ":",
    "::",
    ":::",
    // Missing pieces of the frame.
    "int",
    "int:",
    "int::",
    "int:2",
    "25",
    "-25",
    ":2:25",
    // Lengths that do not line up.
    "int:2:2",              // payload truncated
    "int:5:25",             // length longer than what remains
    "int:1:25",             // length shorter than the payload, so junk follows the frame
    "int:0:25",             // no payload claimed at all
    "int:-1:25",            // negative length
    "int:x:25",             // non-numeric length
    "int:+2:25",            // signed length
    "int: 2:25",            // space in the length
    "int:2 :25",            // space in the length
    "int:1000000000000:25", // length beyond anything a payload can be
    "int:0000000002:25",    // padded past the digits a length may have
    // Junk around a frame that is otherwise fine.
    "int:2:25 ",
    " int:2:25",
    "int:2:25int:2:25",
    // Payloads that are not the type the tag claims.
    "int:2:2a",
    "int:1: ",
    "int:10:2147483648",
    "int:11:-2147483649",
    "int:3:2.5",
    "lng:20:99999999999999999999",
    "sht:6:100000",
    "byt:3:300",
    "flt:3:abc",
    "flt:5:1e999",
    "dbl:6:1e9999",
    "flt:8:infinity",
    "bln:4:TRUE",
    "bln:1:1",
    "bln:3:yes",
    "chr:2:ab",
    "chr:0:",
    // Tags nobody wrote, including M1's untagged format.
    "xyz:2:25",
    "INT:2:25",
    "i:25",
    "b:true",
    // Containers whose payload does not parse to a whole number of elements.
    "lst:9:int:1:1ju",
    "lst:8:int:1:1int",
    "lst:14:int:1:1int:1",
    "lst:7:str:9:abcdefghi", // an element that overruns its container
    "iarr:7:str:1:1",        // element tag the array cannot hold
    "carr:8:chr:2:ab",       // element payload the tag cannot hold
    // Tuples of the wrong width.
    "pair:7:int:1:1",
    "pair:21:int:1:1int:1:2int:1:3",
    "trip:14:int:1:1int:1:2",
    // One level too deep.
    "lst:13:lst:7:int:1:1",
    "lst:20:lst:13:lst:7:int:1:1",
    "pair:20:int:1:1lst:7:int:1:1",
)

/**
 * Perfectly good overrides of some other type, which is what a dormant override looks
 * like once an id is reclaimed — docs/adr/0005-id-derivation-and-dormant-overrides.md.
 * None of them may resolve as an `Int`.
 */
internal val OTHER_TYPE_TAGS: List<String> = listOf(
    "bln:4:true",
    "str:2:25",
    "lng:2:25",
    "sht:2:25",
    "byt:2:25",
    "flt:3:2.5",
    "dbl:3:2.5",
    "chr:1:2",
    "enm:4:FREE",
    "lst:7:int:1:1",
    "set:7:int:1:1",
    "iarr:7:int:1:1",
    "pair:14:int:1:1int:1:2",
)

/** A payload nested far past the one level the format allows, built rather than typed. */
internal fun deeplyNestedEncoding(levels: Int): String {
    var encoded = "int:1:1"
    repeat(levels) { encoded = "lst:${encoded.length}:$encoded" }
    return encoded
}
