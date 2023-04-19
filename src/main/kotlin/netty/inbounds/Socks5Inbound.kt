package netty.inbounds

import io.klogging.NoCoLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.v5.*
import io.netty.util.ReferenceCountUtil
import model.config.Configuration
import model.config.ConfigurationHolder
import model.config.Inbound
import model.config.Outbound
import netty.outbounds.GalaxyOutbound
import java.util.*

class Socks5InitialRequestInboundHandler() : SimpleChannelInboundHandler<DefaultSocks5InitialRequest>(), NoCoLogging {

    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: DefaultSocks5InitialRequest) {
        logger.debug("id :${ctx.channel().id().asShortText()}, initial socks5 request: $msg")
        val failure = msg.decoderResult().isFailure
        if (failure) {
            logger.error("id :${ctx.channel().id().asShortText()}, initial socks5 request decode failure: $msg")
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

class Socks5CommandRequestInboundHandler(private val clientWorkGroup: EventLoopGroup, private val inbound: Inbound) :
    NoCoLogging,
    SimpleChannelInboundHandler<DefaultSocks5CommandRequest>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: DefaultSocks5CommandRequest) {
        val socks5AddressType: Socks5AddressType = msg.dstAddrType()
        if (msg.type() != Socks5CommandType.CONNECT) {
            logger.debug("id: ${ctx.channel().id().asShortText()}, receive commandRequest type=${msg.type()}")
            ReferenceCountUtil.retain(msg)
            ctx.fireChannelRead(msg)
            return
        }
        logger.debug(
            "id: ${
                ctx.channel().id().asShortText()
            }, connect to server, ip=${msg.dstAddr()}, port=${msg.dstPort()}"
        )

        val resolveOutbound = resolveOutbound(inbound)
        resolveOutbound.ifPresent {
            when (it.protocol) {
                "galaxy" -> GalaxyOutbound.galaxyOutbound.outbound(ctx, msg, socks5AddressType, clientWorkGroup)
                else -> {

                }
            }
        }


    }

    private fun resolveOutbound(inbound: Inbound): Optional<Outbound> {
        return ConfigurationHolder.configuration.outbounds.stream().filter { true }.findFirst()
    }


}
