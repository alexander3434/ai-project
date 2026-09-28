package com.aiturbo

import com.aiturbo.config.EnvFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvFileTest {

    @Test
    fun `parses key value lines`() {
        val env = EnvFile.parse(
            """
            DEEPSEEK_API_KEY=sk-secret
            DB_PASSWORD=local-secret
            """.trimIndent(),
        )

        assertEquals("sk-secret", env["DEEPSEEK_API_KEY"])
        assertEquals("local-secret", env["DB_PASSWORD"])
        assertNull(env["MISSING"])
    }

    @Test
    fun `skips blanks and whole-line comments`() {
        val env = EnvFile.parse(
            """
            # a comment
              # an indented comment

            KEY=value

            OTHER=second
            """.trimIndent(),
        )

        assertEquals("value", env["KEY"])
        assertEquals("second", env["OTHER"])
        assertNull(env["a comment"])
    }

    @Test
    fun `a line without a separator is ignored`() {
        val env = EnvFile.parse("JUST_A_WORD\nKEY=value")

        assertNull(env["JUST_A_WORD"])
        assertEquals("value", env["KEY"])
    }

    @Test
    fun `trims keys and values`() {
        val env = EnvFile.parse("  KEY  =  value  ")

        assertEquals("value", env["KEY"])
    }

    @Test
    fun `strips one pair of matching quotes`() {
        val env = EnvFile.parse(
            """
            DOUBLE="double-quoted"
            SINGLE='single-quoted'
            MISMATCHED="unbalanced'
            """.trimIndent(),
        )

        assertEquals("double-quoted", env["DOUBLE"])
        assertEquals("single-quoted", env["SINGLE"])
        assertEquals("\"unbalanced'", env["MISMATCHED"])
    }

    @Test
    fun `an empty value stays an empty string`() {
        val env = EnvFile.parse("KEY=")

        assertEquals("", env["KEY"])
    }

    @Test
    fun `a repeated key keeps the last value`() {
        val env = EnvFile.parse("KEY=first\nKEY=second")

        assertEquals("second", env["KEY"])
    }

    @Test
    fun `does not interpolate variables or strip inline comments`() {
        val env = EnvFile.parse(
            """
            BASE=hello
            DERIVED=${'$'}{BASE}-world
            INLINE=value # not a comment
            """.trimIndent(),
        )

        assertEquals("hello", env["BASE"])
        assertEquals("\${BASE}-world", env["DERIVED"])
        assertEquals("value # not a comment", env["INLINE"])
    }

    @Test
    fun `loads the file of the given directory`() {
        val directory = Files.createTempDirectory("env-file").toFile()
        try {
            File(directory, EnvFile.FILE_NAME).writeText("KEY=from-file\n")

            val env = EnvFile.load(directory.toString())

            assertEquals("from-file", env["KEY"])
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a missing file yields an empty reader`() {
        val directory = Files.createTempDirectory("env-file-missing").toFile()
        try {
            val env = EnvFile.load(directory.toString())

            assertNull(env["ANYTHING"])
        } finally {
            directory.deleteRecursively()
        }
    }
}
