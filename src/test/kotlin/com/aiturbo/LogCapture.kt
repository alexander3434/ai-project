package com.aiturbo

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.aiturbo.log.TraceLog
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Captures the lines a single logger writes while the test runs. Attach it
 * around the code under test and read [lines] afterwards:
 *
 * ```kotlin
 * LogCapture().use { capture ->
 *     ...
 *     assertEquals(1, capture.lines().size)
 * }
 * ```
 */
class LogCapture(
    loggerName: String = TraceLog.LOGGER_NAME,
    level: Level = Level.INFO,
) : Closeable {

    private val logger = LoggerFactory.getLogger(loggerName) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val previousLevel = logger.level

    init {
        logger.level = level
        appender.start()
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = appender.list.toList()

    /** All captured lines, in order. */
    fun lines(): List<String> = events.map { it.formattedMessage }

    /** Captured lines of one level, in order. */
    fun linesAt(level: Level): List<String> =
        events.filter { it.level == level }.map { it.formattedMessage }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}
