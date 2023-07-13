package inbounds


import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v5.*
import model.config.Inbound
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.relayAndOutbound
import utils.ChannelUtils

@Sharable
class SocksServerHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<SocksMessage>() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private var authed = false

    public override fun channelRead0(ctx: ChannelHandlerContext, socksRequest: SocksMessage) {
        when (socksRequest.version()!!) {
            SocksVersion.SOCKS5 -> socks5Connect(ctx, socksRequest)
            else -> {
                ctx.close()
            }
        }
    }

    /**
     * socks5 connect
     */
    private fun socks5Connect(ctx: ChannelHandlerContext, socksRequest: SocksMessage) {
        if (inbound.protocol != "socks5") {
            ctx.close()
            return
        }
        when (socksRequest) {
            is Socks5InitialRequest -> {
                socks5auth(ctx)
            }

            is Socks5PasswordAuthRequest -> {
                socks5DoAuth(socksRequest, ctx)
            }

            is Socks5CommandRequest -> {
                if (inbound.socks5Setting?.auth != null || !authed) {
                    ctx.close()
                }
                if (socksRequest.type() === Socks5CommandType.CONNECT) {
                    ctx.pipeline().addLast(SocksServerConnectHandler(inbound))
                    ctx.pipeline().remove(this)
                    ctx.fireChannelRead(socksRequest)
                } else {
                    ctx.close()
                }
            }

            else -> {
                ctx.close()
            }
        }
    }

    /**
     * socks5 auth
     * if there's auth configuration in inbound setting then do auth
     */
    private fun socks5auth(ctx: ChannelHandlerContext) {
        if (inbound.socks5Setting?.auth != null) {
            ctx.pipeline().addFirst(Socks5PasswordAuthRequestDecoder())
            ctx.write(DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD))
        } else {
            authed = true
            ctx.pipeline().addFirst(Socks5CommandRequestDecoder())
            ctx.write(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
        }
    }

    /**
     * socks5 auth
     * if there's auth configuration in inbound setting then do auth
     */
    private fun socks5DoAuth(socksRequest: Socks5PasswordAuthRequest, ctx: ChannelHandlerContext) {
        if (inbound.socks5Setting?.auth?.username != socksRequest.username() || inbound.socks5Setting?.auth?.password != socksRequest.password()) {
            logger.warn("socks5 auth failed from: ${ctx.channel().remoteAddress()}")
            ctx.write(DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
            ctx.close()
            return
        }
        ctx.pipeline().addFirst(Socks5CommandRequestDecoder())
        ctx.write(DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS))
        authed = true
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, throwable: Throwable) {
        logger.error(throwable.message, throwable)
        ChannelUtils.closeOnFlush(ctx.channel())
    }
}

@Sharable
class SocksServerConnectHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<SocksMessage>() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    public override fun channelRead0(originCTX: ChannelHandlerContext, message: SocksMessage) {
        when (message) {
            is Socks5CommandRequest -> socks5Command(originCTX, message)
            else -> {
                originCTX.close()
            }
        }
    }

    /**
     * socks5 command
     */
    private fun socks5Command(originCTX: ChannelHandlerContext, message: Socks5CommandRequest) {
        val odor = Odor(
            host = message.dstAddr(),
            port = message.dstPort(),
            desProtocol = if (message.type() == Socks5CommandType.UDP_ASSOCIATE) {
                Protocol.UDP
            } else {
                Protocol.TCP
            },
            fromChannel = originCTX.channel().id().asShortText()
        )
        val resolveOutbound = resolveOutbound(inbound, odor)
        logger.info(
            "socks5 inbound: [{}], uri: {}, command: {}",
            originCTX.channel().id().asShortText(),
            "${message.dstAddr()}:${message.dstPort()}",
            message.type()
        )
        resolveOutbound.ifPresent { outbound ->

            relayAndOutbound(
                RelayAndOutboundOp(
                    originCTX = originCTX,
                    outbound = outbound,
                    odor = odor
                ).also {relayAndOutboundOp ->
                    relayAndOutboundOp.connectEstablishedCallback = {
                        originCTX.channel().writeAndFlush(
                            DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS,
                                message.dstAddrType(),
                                message.dstAddr(),
                                message.dstPort()
                            )
                        ).addListener(ChannelFutureListener {
                            originCTX.pipeline().remove(this@SocksServerConnectHandler)
                        })
                    }
                    relayAndOutboundOp.connectFail = {
                        originCTX.close()
                    }
                }
            )
        }

    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause.message, cause)
        ChannelUtils.closeOnFlush(ctx.channel())
    }
}
