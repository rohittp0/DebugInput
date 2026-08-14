package com.rohittp.debuginput.compiler.fir

/**
 * Turns a raw KDoc block into the text the page shows behind its info icon.
 *
 * What it does and why:
 * - drops block tags (`@param`, `@see`, `@property`, …) and everything after the first one, since
 *   they document an API rather than the value, and nothing on a phone-sized popup can use them;
 * - flattens `[com.lascade.ta.shared.constants.DEFAULT_FLAG_SCALE]` to `DEFAULT_FLAG_SCALE`, so a
 *   sentence stays readable without the IDE that would have made the link clickable;
 * - collapses the leading `*` margin and hard-wrapped lines into paragraphs, blank lines kept as
 *   paragraph breaks.
 *
 * It keeps the whole body. These docs are the reason someone can safely change the number, so
 * truncating them would remove the only thing that makes the page safe to use.
 */
internal fun kdocToDocs(raw: String): String {
    val body = raw
        .removePrefix("/**")
        .removeSuffix("*/")
        .lines()
        .map { it.trim().removePrefix("*").trim() }

    val paragraphs = mutableListOf<StringBuilder>()
    for (line in body) {
        if (line.startsWith("@")) break
        if (line.isEmpty()) {
            if (paragraphs.lastOrNull()?.isNotEmpty() == true) paragraphs += StringBuilder()
            continue
        }
        val current = paragraphs.lastOrNull() ?: StringBuilder().also { paragraphs += it }
        if (current.isNotEmpty()) current.append(' ')
        current.append(flattenLinks(line))
    }

    return paragraphs.filter { it.isNotEmpty() }.joinToString("\n\n") { it.toString() }
}

/**
 * `[a.b.C]` becomes `C`. A qualified link is left as its last segment because that is what the
 * writer would have said out loud; an unqualified one loses only its brackets.
 *
 * `[text](url)` markdown links are left alone: the bracketed part is already the readable text.
 */
private fun flattenLinks(line: String): String {
    val out = StringBuilder(line.length)
    var at = 0
    while (at < line.length) {
        val open = line.indexOf('[', at)
        if (open < 0) {
            out.append(line, at, line.length)
            break
        }
        val close = line.indexOf(']', open + 1)
        if (close < 0) {
            out.append(line, at, line.length)
            break
        }
        out.append(line, at, open)
        val target = line.substring(open + 1, close)
        val isMarkdownLink = close + 1 < line.length && line[close + 1] == '('
        if (isMarkdownLink) {
            out.append(line, open, close + 1)
        } else {
            out.append(target.substringAfterLast('.'))
        }
        at = close + 1
    }
    return out.toString()
}
