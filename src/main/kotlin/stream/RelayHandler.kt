package stream

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel

/**
 * relay outbound to an embedded channel
 */
class RelayOutBound2EmbeddedChannelHandler(private val channel: EmbeddedChannel) :
    ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        channel.writeOutbound(msg)
    }
}

/**
 * relay inbound to an embedded channel
 */
class RelayInBound2EmbeddedChannelHandler(private val channel: EmbeddedChannel) :
    ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        channel.writeInbound(msg)
    }
}


/**
 * relay inbound to ctx
 */
class RelayInBound2CTXHandler(private val channelHandlerContext: ChannelHandlerContext) :
    ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        channelHandlerContext.fireChannelRead(msg)
    }
}

/**
 * relay outbound to ctx
 */
class RelayOutBound2CTXHandler(private val channelHandlerContext: ChannelHandlerContext) :
    ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        channelHandlerContext.writeAndFlush(msg, channelHandlerContext.newPromise().addListener {
            if (it.isSuccess) {
                promise.setSuccess()
            } else {
                promise.setFailure(it.cause())
            }
        })
    }
}
