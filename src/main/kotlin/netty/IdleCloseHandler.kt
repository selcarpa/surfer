package netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import mu.KotlinLogging
import utils.closeOnFlush

private val logger = KotlinLogging.logger {}

/**
 * when channel idle, close it
 */
class IdleCloseHandler : ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is IdleStateEvent -> {
                ctx.channel().closeOnFlush()
            }
        }
        logger.trace { "[${ctx.channel().id()}] userEventTriggered: $evt" }
        super.userEventTriggered(ctx, evt)
    }
}
