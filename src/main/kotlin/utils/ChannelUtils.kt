package utils

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelPipeline
import model.*
import io.github.oshai.kotlinlogging.KotlinLogging


private val logger = KotlinLogging.logger {}

/**
 * Closes the specified channel after all queued write requests are flushed.
 */
fun Channel.closeOnFlush() {
    logger.debug { "closeOnFlush, [${this.id().asShortText()}]" }
    if (this.isActive) {
        this.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
    }
}

private val handlerNameWhiteList = arrayListOf(
    RELAY_HANDLER_NAME,
    PROXY_HANDLER_NAME,
    TROJAN_PROXY_OUTBOUND,
    LOG_HANDLER,
    IDLE_CLOSE_HANDLER,
    IDLE_CHECK_HANDLER,
    GLOBAL_TRAFFIC_SHAPING
)

fun ChannelPipeline.cleanHandlers() {
    this.names().forEach {
        if (!handlerNameWhiteList.contains(it) && this.context(it) != null) {
            this.remove(it)
        }
    }
}
