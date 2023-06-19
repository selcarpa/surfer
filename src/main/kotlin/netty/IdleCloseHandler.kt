package netty

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent

/**
 * when channel idle, close it
 */
class IdleCloseHandler : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {
        when (evt) {
            is IdleStateEvent -> {
                ctx?.close()
            }
        }
        super.userEventTriggered(ctx, evt)
    }
}
