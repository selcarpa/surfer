package protocol

import io.netty5.channel.ChannelHandlerContext
import io.netty5.channel.SimpleChannelInboundHandler
import mu.KotlinLogging

class DiscardHandler : SimpleChannelInboundHandler<Any>() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    override fun messageReceived(ctx: ChannelHandlerContext, msg: Any) {
        // Discard the received data silently.
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun channelExceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause){
            "discard handler exception caught, [${ctx.channel().id()}], pipeline: ${
                ctx.channel().pipeline().names()
            }, message: ${cause.message}"
        }
        ctx.close()
    }
}
