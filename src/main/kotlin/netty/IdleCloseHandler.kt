package netty

import io.netty5.channel.ChannelHandler
import io.netty5.channel.ChannelHandlerContext
import io.netty5.handler.timeout.IdleStateEvent

/**
 * when channel idle, close it
 */
class IdleCloseHandler : ChannelHandler {
    override fun channelInboundEvent(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is IdleStateEvent -> {
                ctx.close()
            }
        }
        super.channelInboundEvent(ctx, evt)
    }


}
