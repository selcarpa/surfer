package log

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import model.config.ConfigurationSettings.Companion.Configuration
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets


fun loadLogConfig() {

    // clear all appenders and present log instance
    val logCtx: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val log = logCtx.getLogger(Logger.ROOT_LOGGER_NAME)
    log.detachAndStopAllAppenders()
    log.isAdditive = false


    val logConfiguration = Configuration.log
    // set log level
    log.level = (if (logConfiguration == null) Level.INFO else Level.toLevel(logConfiguration.level))

    // default console print
    val logEncoder = PatternLayoutEncoder()
    logEncoder.context = logCtx
    logEncoder.pattern =
        logConfiguration?.pattern ?: "%date{ISO8601} %highlight(%level) [%t] %cyan(%logger{16}) %M: %msg%n"
    logEncoder.charset = StandardCharsets.UTF_8
    logEncoder.start()

    val logConsoleAppender: ConsoleAppender<*> = ConsoleAppender<Any?>()
    logConsoleAppender.context = logCtx
    logConsoleAppender.name = "console"
    logConsoleAppender.encoder = logEncoder
    logConsoleAppender.start()

    //async print to console
    val asyncLogConsoleAppender = AsyncAppender()
    asyncLogConsoleAppender.context = logCtx
    asyncLogConsoleAppender.name = "asyncConsole"
    asyncLogConsoleAppender.addAppender(logConsoleAppender as Appender<ILoggingEvent>)
    asyncLogConsoleAppender.start()
//    log.addAppender(asyncLogConsoleAppender)
    log.addAppender(logConsoleAppender as Appender<ILoggingEvent>)


    //if log configuration is null, use default configuration
    if (logConfiguration != null && logConfiguration.fileName.isNotEmpty()) {


        val rollingFileAppender: RollingFileAppender<*> = RollingFileAppender<Any?>()

        rollingFileAppender.context = logCtx
        rollingFileAppender.name = "logFile"
        rollingFileAppender.encoder = logEncoder
        rollingFileAppender.isAppend = true
        rollingFileAppender.file = "${logConfiguration.path}${File.separator}${logConfiguration.fileName}.log"

        //init log rolling policy
        val logFilePolicy: SizeAndTimeBasedRollingPolicy<*> = SizeAndTimeBasedRollingPolicy<Any?>()
        logFilePolicy.context = logCtx
        logFilePolicy.setParent(rollingFileAppender)
        logFilePolicy.fileNamePattern =
            "${logConfiguration.path}/${logConfiguration.fileName}-%d{yyyy-MM-dd_HH}-%i.log.zip"
        logFilePolicy.maxHistory = logConfiguration.maxHistory
        logFilePolicy.setMaxFileSize(FileSize.valueOf("20mb"))
        logFilePolicy.start()

        rollingFileAppender.rollingPolicy = logFilePolicy
        rollingFileAppender.start()

        log.isAdditive = false
        log.level = Level.toLevel(logConfiguration.level)
        log.addAppender(rollingFileAppender as Appender<ILoggingEvent>)
    }

    //todo log context event listener not working
}


