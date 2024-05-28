package log

import kotlinx.coroutines.delay
import io.github.oshai.kotlinlogging.KotlinLogging
import netty.ProxyChannelInitializer.Companion.globalTrafficShapingHandler

private val logger = KotlinLogging.logger {}

suspend fun startSpeedStatisticPrintPerSeconds() {
    while (true) {
        speedPrint()
        delay(1000)
    }
}

fun speedPrint() {
    val trafficCounter = globalTrafficShapingHandler.trafficCounter()
    val lastReadBytes = trafficCounter.lastReadBytes()
    when {
        lastReadBytes > 1024 * 1024 * 1024 -> {
            logger.debug { "read speed: ${lastReadBytes / 1024 / 1024 / 1024} GB/s" }
        }

        lastReadBytes > 1024 * 1024 -> {
            logger.debug { "read speed: ${lastReadBytes / 1024 / 1024} MB/s" }
        }

        lastReadBytes > 1024 -> {
            logger.debug { "read speed: ${lastReadBytes / 1024} KB/s" }
        }

        else -> {
            logger.debug { "read speed: $lastReadBytes B/s" }
        }
    }

    val lastWrittenBytes = trafficCounter.lastWrittenBytes()
    when {
        lastWrittenBytes > 1024 * 1024 * 1024 -> {
            logger.debug { "write speed: ${lastWrittenBytes / 1024 / 1024 / 1024} GB/s" }
        }

        lastWrittenBytes > 1024 * 1024 -> {
            logger.debug { "write speed: ${lastWrittenBytes / 1024 / 1024} MB/s" }
        }

        lastWrittenBytes > 1024 -> {
            logger.debug { "write speed: ${lastWrittenBytes / 1024} KB/s" }
        }

        else -> {
            logger.debug { "write speed: $lastWrittenBytes B/s" }
        }
    }
}
