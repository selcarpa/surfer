package netty.stream

import io.klogging.NoCoLogging
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.Promise
import utils.ChannelUtils

/**
 * relay both server and client
 */
open class RelayHandler(private val relayChannel: Channel) : ChannelInboundHandlerAdapter(), NoCoLogging {
    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (relayChannel.isActive) {
            logger.debug(
                "${relayChannel.id().asShortText()} pipeline handlers:${
                    relayChannel.pipeline().names()
                }, write message:${msg.javaClass.name}"
            )
            relayChannel.writeAndFlush(msg).addListener {
                if (!it.isSuccess) {
                    logger.error("write message:${msg.javaClass.name} to ${relayChannel.id().asShortText()} failed", it.cause())
                    logger.error(it.cause())
                }
            }
        } else {
            logger.error("relay channel is not active, close message:${msg.javaClass.name}")
            ReferenceCountUtil.release(msg)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (relayChannel.isActive) {
            ChannelUtils.closeOnFlush(relayChannel)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause)
        ctx.close()
    }
}

class PromiseHandler(private val promise: Promise<Channel>) : ChannelInboundHandlerAdapter() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.pipeline().remove(this)
        //init promise value when connect to destination server
        promise.setSuccess(ctx.channel())
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, throwable: Throwable) {
        promise.setFailure(throwable)
    }
}


