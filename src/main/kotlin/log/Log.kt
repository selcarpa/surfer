package log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import model.config.ConfigurationSettings.Companion.Configuration
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets


fun loadLogConfig() {

    val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val logConfiguration = Configuration.log
    //if log configuration is null, use default configuration
    if (logConfiguration == null || logConfiguration.fileName.isEmpty()) {

        val logEncoder = PatternLayoutEncoder()
        logEncoder.context = logCtx
        logEncoder.pattern = logConfiguration?.pattern ?: "%date{ISO8601} %highlight(%level) [%t] %cyan(%logger{16}) %M: %msg%n"
        logEncoder.charset = StandardCharsets.UTF_8
        logEncoder.start()

        val logConsoleAppender: ConsoleAppender<*> = ConsoleAppender<Any?>()
        logConsoleAppender.context = logCtx
        logConsoleAppender.name = "console"
        logConsoleAppender.encoder = logEncoder
        logConsoleAppender.start()

        val log = logCtx.getLogger(Logger.ROOT_LOGGER_NAME)
        log.detachAndStopAllAppenders()
        log.isAdditive = false
        log.level = (if (logConfiguration == null) Level.INFO else Level.toLevel(logConfiguration.level))
        log.addAppender(logConsoleAppender as Appender<ILoggingEvent>)
        return
    }

    val logEncoder = PatternLayoutEncoder()
    logEncoder.context = logCtx
    logEncoder.pattern = logConfiguration.pattern
    logEncoder.start()

    val logFileAppender: RollingFileAppender<*> = RollingFileAppender<Any?>()
    logFileAppender.context = logCtx
//    logFileAppender.name = "logFile"
    logFileAppender.encoder = logEncoder
    logFileAppender.isAppend = true
    logFileAppender.file = logConfiguration.fileName

    val logFilePolicy: TimeBasedRollingPolicy<*> = TimeBasedRollingPolicy<Any?>()
    logFilePolicy.context = logCtx
    logFilePolicy.setParent(logFileAppender)
    logFilePolicy.fileNamePattern = "logs/logfile-%d{yyyy-MM-dd_HH}.log"
    logFilePolicy.maxHistory = logConfiguration.maxHistory
    logFilePolicy.start()

    logFileAppender.rollingPolicy = logFilePolicy
    logFileAppender.start()

    val log: Logger = logCtx.getLogger(Logger.ROOT_LOGGER_NAME)
    log.isAdditive = false
    log.level = Level.toLevel(logConfiguration.level)
    log.addAppender(logFileAppender as Appender<ILoggingEvent>)
}
