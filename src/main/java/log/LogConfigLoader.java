package log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import model.LogLevel;
import model.config.LogConfiguration;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.io.File;


public class LogConfigLoader {

    public static void loadLogConfig(LogConfiguration logConfiguration) {

        // clear all appenders and present log instance
        LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();
        //todo log context event listener not working
        Logger log = logCtx.getLogger(Logger.ROOT_LOGGER_NAME);
        log.detachAndStopAllAppenders();
        log.setAdditive(false);

        // set log level
        log.setLevel(LogLevel.by(logConfiguration.getLevel()).toLogBackLevel());

        if (log.getLevel() == Level.OFF) {
            return;
        }

        // default console print
        PatternLayoutEncoder logEncoder = new PatternLayoutEncoder();
        logEncoder.setContext(logCtx);
        logEncoder.setPattern(logConfiguration.getPattern());
        logEncoder.setCharset(StandardCharsets.UTF_8);
        logEncoder.start();

        ConsoleAppender<ILoggingEvent> logConsoleAppender = new ConsoleAppender<>();
        logConsoleAppender.setContext(logCtx);
        logConsoleAppender.setName("console");
        logConsoleAppender.setEncoder(logEncoder);
        logConsoleAppender.start();
        log.addAppender(logConsoleAppender);

        //if log configuration is null, use default configuration
        if (!logConfiguration.getFileName().isEmpty()) {

            RollingFileAppender<ILoggingEvent> rollingFileAppender = new RollingFileAppender<>();

            rollingFileAppender.setContext(logCtx);
            rollingFileAppender.setName("logFile");
            rollingFileAppender.setEncoder(logEncoder);
            rollingFileAppender.setAppend(true);
            rollingFileAppender.setFile(logConfiguration.getPath().replaceAll("/$", "") + File.separator + logConfiguration.getFileName().replaceAll("^/", "") + ".log");

            //init log rolling policy
            SizeAndTimeBasedRollingPolicy<ILoggingEvent> logFilePolicy = new SizeAndTimeBasedRollingPolicy<>();
            logFilePolicy.setContext(logCtx);
            logFilePolicy.setParent(rollingFileAppender);
            logFilePolicy.setFileNamePattern(logConfiguration.getPath().replaceAll("/$", "") + File.separator + logConfiguration.getFileName().replaceAll("^/", "") + "-%d{yyyy-MM-dd}-%i.log.zip");
            logFilePolicy.setMaxHistory(logConfiguration.getMaxHistory());
            logFilePolicy.setMaxFileSize(FileSize.valueOf(logConfiguration.getMaxFileSize()));
            logFilePolicy.start();

            rollingFileAppender.setRollingPolicy(logFilePolicy);
            rollingFileAppender.start();

            log.setAdditive(false);
            log.setLevel(Level.toLevel(logConfiguration.getLevel()));
            log.addAppender(rollingFileAppender);
        }
    }
}
