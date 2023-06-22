package stream

import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.ssl.SslCompletionEvent
import io.netty.util.concurrent.Promise
import mu.KotlinLogging

/**
 * ssl activator for client connected, when ssl handshake complete, we can activate other operation
 */
class SslActiveHandler(private val promise: Promise<Channel>) : ChannelDuplexHandler() {
    private val logger = KotlinLogging.logger {}
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is SslCompletionEvent) {
            logger.trace { "SslCompletionEvent: $evt" }
            promise.setSuccess(ctx.channel())
            ctx.channel().pipeline().remove(this)
        }
        ctx.fireUserEventTriggered(evt)
    }
}
