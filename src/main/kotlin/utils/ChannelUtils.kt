package utils

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import mu.KotlinLogging

object ChannelUtils {

    private val logger = KotlinLogging.logger {}

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    fun closeOnFlush(ch: Channel) {
        logger.debug { "closeOnFlush, [${ch.id().asShortText()}]" }
        if (ch.isActive) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
        }
    }
}
