package netty

import io.klogging.NoCoLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.v5.*
import io.netty.util.ReferenceCountUtil

class Socks5InitialRequestInboundHandler : SimpleChannelInboundHandler<DefaultSocks5InitialRequest>(), NoCoLogging {

    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: DefaultSocks5InitialRequest) {
        logger.debug("initial socks5 request: $msg")
        val failure = msg.decoderResult().isFailure
        if (failure) {
            logger.error("initial socks5 request decode failure: $msg")
            ReferenceCountUtil.retain(msg)
            ctx.fireChannelRead(msg)
            return
        }

        val socks5InitialResponse: Socks5InitialResponse = DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH)
        ctx.writeAndFlush(socks5InitialResponse)
        ReferenceCountUtil.retain(msg)
        ctx.fireChannelRead(msg)
    }
}
