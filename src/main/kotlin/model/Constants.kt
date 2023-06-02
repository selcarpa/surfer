package model

const val RELAY_HANDLER_NAME = "relay_handler"

enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, OFF;
    companion object{
        fun by(levelString: String) = when (levelString.lowercase()) {
            "trace" -> TRACE
            "debug" -> DEBUG
            "info" -> INFO
            "warn" -> WARN
            "error" -> ERROR
            "off" -> OFF
            else -> INFO
        }
    }


    fun toNettyLogLevel(): io.netty.handler.logging.LogLevel? = when (this) {
        TRACE -> io.netty.handler.logging.LogLevel.TRACE
        DEBUG -> io.netty.handler.logging.LogLevel.DEBUG
        INFO -> io.netty.handler.logging.LogLevel.INFO
        WARN -> io.netty.handler.logging.LogLevel.WARN
        ERROR -> io.netty.handler.logging.LogLevel.ERROR
        else -> null
    }

    fun toLogBackLevel(): ch.qos.logback.classic.Level = when (this) {
        TRACE -> ch.qos.logback.classic.Level.TRACE
        DEBUG -> ch.qos.logback.classic.Level.DEBUG
        INFO -> ch.qos.logback.classic.Level.INFO
        WARN -> ch.qos.logback.classic.Level.WARN
        ERROR -> ch.qos.logback.classic.Level.ERROR
        OFF -> ch.qos.logback.classic.Level.OFF
    }



}
