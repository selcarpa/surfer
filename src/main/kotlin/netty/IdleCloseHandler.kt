package netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import mu.KotlinLogging

/**
 * when channel idle, close it
 */
class IdleCloseHandler : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is IdleStateEvent -> {
                ctx.close()
            }
        }
        logger.trace { "[${ctx.channel().id()}] userEventTriggered: $evt" }
        super.userEventTriggered(ctx, evt)
    }
}
