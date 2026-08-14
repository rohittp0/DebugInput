package com.rohittp.debuginput.compiler.fir

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens

/**
 * The raw KDoc block attached to this declaration, `/**` and `*/` included, or null when it has
 * none.
 *
 * Read through the light-tree view rather than through PSI. Every [KtSourceElement] exposes
 * `treeStructure` and `lighterASTNode` whichever way the file was parsed, so this is the one
 * mechanism that answers under both the CLI's LightTree parser and the IDE's PSI parser. A
 * mechanism that worked under only one would mean docs in the IDE and empty docs in the real
 * build.
 */
internal fun KtSourceElement.rawKDoc(): String? {
    val children = Ref<Array<LighterASTNode?>>()
    val count = treeStructure.getChildren(lighterASTNode, children)
    val nodes = children.get() ?: return null
    try {
        for (index in 0 until count) {
            val child = nodes[index] ?: continue
            if (child.tokenType == KDocTokens.KDOC) return treeStructure.toString(child).toString()
        }
    } finally {
        treeStructure.disposeChildren(nodes, count)
    }
    return null
}
