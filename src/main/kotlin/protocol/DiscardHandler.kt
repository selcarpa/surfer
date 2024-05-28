package protocol

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}
class DiscardHandler : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // Discard the received data silently.
        logger.trace { "[${ctx.channel().id()}] discard handler received message: $msg" }
        ReferenceCountUtil.release(msg)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) {
            "[${ctx.channel().id()}] discard handler exception caught, pipeline: ${
                ctx.channel().pipeline().names()
            }, message: ${cause.message}"
        }
        ctx.close()
    }
}
