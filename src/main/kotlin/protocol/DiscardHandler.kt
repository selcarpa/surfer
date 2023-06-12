package protocol

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import mu.KotlinLogging

class DiscardHandler : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // Discard the received data silently.
        ReferenceCountUtil.release(msg)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause){
            "discard handler exception caught, [${ctx.channel().id()}], pipeline: ${
                ctx.channel().pipeline().names()
            }, message: ${cause.message}"
        }
        ctx.close()
    }
}
