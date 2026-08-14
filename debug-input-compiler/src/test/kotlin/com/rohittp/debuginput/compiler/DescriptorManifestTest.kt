package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.compiler.ir.DebugInputSite
import com.rohittp.debuginput.compiler.ir.descriptorManifestJson
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The JSON the Gradle plugin reads. Its shape is a contract, so assert it literally. */
class DescriptorManifestTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a module with inputs lists every id and type key`() {
        val manifest = File(temp.newFolder(), "nested/domain-descriptors.json")

        compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10

                @DebugInput private val gravity = 9
                """.trimIndent(),
            ),
            module = ":domain",
            manifestOut = manifest,
        ).assertSucceeded()

        assertEquals(
            """{"module":":domain",""" +
                """"function":"com.rohittp.debuginput.generated.descriptors_domain",""" +
                """"inputs":[{"id":"com.app.physics.speed","typeKey":"kotlin.Int"},""" +
                """{"id":"com.app.physics.Physics.kt.gravity","typeKey":"kotlin.Int"}]}""",
            manifest.readText(),
        )
    }

    @Test
    fun `an instrumented module with no inputs still writes a manifest`() {
        val manifest = File(temp.newFolder(), "app-descriptors.json")

        compile(
            temp.newFolder(),
            SourceFile("Empty.kt", "package com.app\n"),
            module = ":app",
            manifestOut = manifest,
        ).assertSucceeded()

        // "Instrumented, declared nothing" has to be distinguishable from "never instrumented".
        assertEquals(
            """{"module":":app","function":"com.rohittp.debuginput.generated.descriptors_app","inputs":[]}""",
            manifest.readText(),
        )
    }

    @Test
    fun `no manifest is written when the option is absent`() {
        val workDir = temp.newFolder()

        compile(
            workDir,
            SourceFile("Empty.kt", "package com.app\n"),
            module = ":app",
        ).assertSucceeded()

        assertTrue(workDir.walkTopDown().none { it.extension == "json" })
    }

    @Test
    fun `strings that would break the JSON are escaped`() {
        val json = descriptorManifestJson(
            module = ":say \"hi\"\n\\path",
            sites = emptyList<DebugInputSite>(),
        )

        assertTrue("""":say \"hi\"\n\\path"""" in json, json)
    }
}
