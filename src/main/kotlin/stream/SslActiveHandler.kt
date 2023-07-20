package stream

import io.netty5.channel.Channel
import io.netty5.channel.ChannelHandler
import io.netty5.channel.ChannelHandlerContext
import io.netty5.handler.ssl.SslCompletionEvent
import io.netty5.util.concurrent.Promise
import mu.KotlinLogging

/**
 * ssl activator for client connected, when ssl handshake complete, we can activate other operation
 */
class SslActiveHandler(private val promise: Promise<Channel>) : ChannelHandler {
    private val logger = KotlinLogging.logger {}

    override fun channelInboundEvent(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is SslCompletionEvent) {
            logger.trace { "SslCompletionEvent: $evt" }
            promise.setSuccess(ctx.channel())
            ctx.channel().pipeline().remove(this)
        }
        ctx.fireChannelInboundEvent(evt)
    }
}
