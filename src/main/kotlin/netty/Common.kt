package netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Auto exec handler
 */
class ActiveAutoExecHandler(private val exec: (ChannelHandlerContext) -> Unit) : ChannelInboundHandlerAdapter() {

    override fun channelActive(ctx: ChannelHandlerContext) {
        exec(ctx)
        ctx.pipeline().remove(this)
        super.channelActive(ctx)
    }
}

/**
 * Exception caught
 */
class ExceptionCaughtHandler : ChannelInboundHandlerAdapter() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) { "[${ctx.channel().id().asShortText()}] Exception caught" }
    }
}

/**
 * Exposure events
 */
class EventTriggerHandler(val callBack: (ChannelHandlerContext, Any) -> Unit) : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        logger.trace { "[${ctx.channel().id().asShortText()}] User event triggered: $evt" }
        callBack(ctx, evt)
        super.userEventTriggered(ctx, evt)
    }
}
