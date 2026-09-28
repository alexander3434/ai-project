package com.aiturbo.config

import java.io.File
import java.io.IOException

/**
 * Minimal stdlib `.env` reader (ASM-04). Never logs, never exposes values (`toString` is
 * intentionally not overridden and the class is not a data class).
 */
class EnvFile private constructor(private val values: Map<String, String>) {

    operator fun get(key: String): String? = values[key]

    companion object {
        const val FILE_NAME = ".env"

        /**
         * `KEY=VALUE` lines; whole-line `#` comments, blank lines, optional surrounding quotes.
         *
         * Rules: split on the first `=`, trim key and value, strip one pair of matching
         * surrounding single/double quotes, no interpolation, no inline-comment stripping,
         * an empty value is an empty string, a repeated key keeps the last value and a line
         * without `=` is ignored.
         */
        fun parse(text: String): EnvFile {
            val values = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val separator = trimmed.indexOf('=')
                if (separator < 0) return@forEach
                val key = trimmed.substring(0, separator).trim()
                if (key.isEmpty()) return@forEach
                values[key] = unquote(trimmed.substring(separator + 1).trim())
            }
            return EnvFile(values)
        }

        /** The `.env` in [directory]; missing or unreadable → empty reader (ignore-if-missing). */
        fun load(directory: String = System.getProperty("user.dir"), fileName: String = FILE_NAME): EnvFile =
            try {
                parse(File(directory, fileName).readText())
            } catch (_: IOException) {
                EnvFile(emptyMap())
            }

        private fun unquote(value: String): String {
            if (value.length < 2) return value
            val first = value.first()
            if ((first == '"' || first == '\'') && value.last() == first) {
                return value.substring(1, value.length - 1)
            }
            return value
        }
    }
}
