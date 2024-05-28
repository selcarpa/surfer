package netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Auto exec handler
 */
class AutoExecHandler(private val exec: (ChannelHandlerContext) -> Unit) : ChannelInboundHandlerAdapter() {
    override fun handlerAdded(ctx: ChannelHandlerContext) {
        exec(ctx)
        ctx.pipeline().remove(this)
        super.handlerAdded(ctx)
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
class EventTriggerHandler(val callBack: (ChannelHandlerContext, Any) -> Boolean) : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        logger.trace { "[${ctx.channel().id().asShortText()}] User event triggered: $evt" }
        if (callBack(ctx, evt)) {
            return
        }
        super.userEventTriggered(ctx, evt)
    }
}
