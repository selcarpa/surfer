package stream

import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel

private val logger = KotlinLogging.logger {}

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
        logger.trace {
            "relay inbound read from [${ctx.channel().id().asShortText()}] pipeline handlers:${
                ctx.channel().pipeline().names()
            }, to [${channelHandlerContext.channel().id().asShortText()}] pipeline handlers:${
                channelHandlerContext.pipeline().names()
            }, write message:${msg.javaClass.name}"
        }
        channelHandlerContext.fireChannelRead(msg)
    }
}

/**
 * relay outbound to ctx
 */
class RelayOutBound2CTXHandler(private val channelHandlerContext: ChannelHandlerContext) :
    ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        logger.trace {
            "relay inbound read from [${ctx.channel().id().asShortText()}] pipeline handlers:${
                ctx.channel().pipeline().names()
            }, to [${channelHandlerContext.channel().id().asShortText()}] pipeline handlers:${
                channelHandlerContext.pipeline().names()
            }, write message:${msg.javaClass.name}"
        }
        channelHandlerContext.writeAndFlush(msg, channelHandlerContext.newPromise().addListener {
            if (it.isSuccess) {
                promise.setSuccess()
            } else {
                promise.setFailure(it.cause())
            }
        })
    }
}
