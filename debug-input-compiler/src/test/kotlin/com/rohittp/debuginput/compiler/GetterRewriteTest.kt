package com.rohittp.debuginput.compiler

import com.rohittp.debuginput.DebugInputRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the transform is for: a plain read of an input returns the override when one is
 * set, and the id the getter passes is the one ADR-0005 specifies.
 */
class GetterRewriteTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Before
    fun resetRegistry() {
        DebugInputRegistry.reset()
    }

    @Test
    fun `top level input resolves through the registry`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput(docs = "Player speed in m/s")
                val speed = 10

                fun readSpeed(): Int = speed
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertEquals(10, result.readSpeed())
        assertEquals(
            listOf(DebugInputRegistry.Call("com.app.physics.speed", "resolveInt", 10)),
            DebugInputRegistry.calls,
        )
    }

    @Test
    fun `override wins over the default`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10

                fun readSpeed(): Int = speed
                """.trimIndent(),
            ),
        ).assertSucceeded()

        DebugInputRegistry.overrides["com.app.physics.speed"] = 25

        assertEquals(25, result.readSpeed())
    }

    @Test
    fun `an input on an object is identified by the enclosing class`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Config.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput

                object Config {
                    @DebugInput val timeout = 5
                }

                fun readTimeout(): Int = Config.timeout
                """.trimIndent(),
            ),
        ).assertSucceeded()

        DebugInputRegistry.overrides["com.app.Config.timeout"] = 30

        val readTimeout = result.classLoader()
            .loadClass("com.app.ConfigKt")
            .getMethod("readTimeout")
        assertEquals(30, readTimeout.invoke(null))
    }

    @Test
    fun `a nested declaration contributes every enclosing name to the id`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Config.kt",
                """
                package com.app

                import com.rohittp.debuginput.DebugInput

                object Config {
                    object Network {
                        @DebugInput val timeout = 5
                    }
                }

                fun readTimeout(): Int = Config.Network.timeout
                """.trimIndent(),
            ),
        ).assertSucceeded()

        DebugInputRegistry.overrides["com.app.Config.Network.timeout"] = 7

        val readTimeout = result.classLoader().loadClass("com.app.ConfigKt").getMethod("readTimeout")
        assertEquals(7, readTimeout.invoke(null))
    }

    @Test
    fun `two private top level inputs in one package keep separate ids`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput private val speed = 10

                fun readPhysicsSpeed(): Int = speed
                """.trimIndent(),
            ),
            SourceFile(
                "Wind.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput private val speed = 3

                fun readWindSpeed(): Int = speed
                """.trimIndent(),
            ),
        ).assertSucceeded()

        DebugInputRegistry.overrides["com.app.physics.Physics.kt.speed"] = 99

        val loader = result.classLoader()
        val physics = loader.loadClass("com.app.physics.PhysicsKt").getMethod("readPhysicsSpeed")
        val wind = loader.loadClass("com.app.physics.WindKt").getMethod("readWindSpeed")

        assertEquals(99, physics.invoke(null))
        assertEquals(3, wind.invoke(null))
    }

    @Test
    fun `a read from inside the declaring class still goes through the registry`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                class Physics {
                    @DebugInput private val speed = 10

                    fun report(): Int = speed
                }

                fun readSpeed(): Int = Physics().report()
                """.trimIndent(),
            ),
        ).assertSucceeded()

        DebugInputRegistry.overrides["com.app.physics.Physics.speed"] = 42

        assertEquals(42, result.readSpeed())
    }

    @Test
    fun `an Android release compilation leaves the getter alone`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                import com.rohittp.debuginput.DebugInput

                @DebugInput val speed = 10

                fun readSpeed(): Int = speed
                """.trimIndent(),
            ),
            enabled = false,
        ).assertSucceeded()

        DebugInputRegistry.overrides["com.app.physics.speed"] = 25

        assertEquals(10, result.readSpeed())
        assertTrue(DebugInputRegistry.calls.isEmpty())
    }

    @Test
    fun `an unannotated val is left alone`() {
        val result = compile(
            temp.newFolder(),
            SourceFile(
                "Physics.kt",
                """
                package com.app.physics

                val speed = 10

                fun readSpeed(): Int = speed
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertEquals(10, result.readSpeed())
        assertTrue(DebugInputRegistry.calls.isEmpty())
    }

    private fun CompilationResult.readSpeed(): Int =
        classLoader().loadClass("com.app.physics.PhysicsKt").getMethod("readSpeed").invoke(null) as Int
}
