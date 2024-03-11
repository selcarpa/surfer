package netty

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
class AutoSuccessHandler(private val exec: (ChannelHandlerContext) -> Unit) : ChannelInboundHandlerAdapter() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        exec(ctx)
        if (ctx.pipeline().context("AutoSuccessHandler") != null) {
            ctx.pipeline().remove(this)
        }
        super.channelActive(ctx)
    }
}

class ExceptionCaughtHandler : ChannelInboundHandlerAdapter() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
     logger.error(cause) { "Exception caught" }
    }
}
