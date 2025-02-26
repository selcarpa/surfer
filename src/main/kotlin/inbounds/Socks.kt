package inbounds


import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v5.*
import model.config.Inbound
import model.config.Socks5Setting
import model.protocol.Odor
import model.protocol.Protocol
import io.github.oshai.kotlinlogging.KotlinLogging
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.relayAndOutbound
import utils.closeOnFlush

private val logger = KotlinLogging.logger {}

class SocksServerHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<SocksMessage>() {

    private var authed = false
    private var socks5Setting: Socks5Setting? = null

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
        when (socksRequest) {
            is Socks5InitialRequest -> {
                socks5auth(ctx)
            }

            is Socks5PasswordAuthRequest -> {
                socks5DoAuth(socksRequest, ctx)
            }

            is Socks5CommandRequest -> {
                // because we compat with no socks5Setting item in socks5Settings,
                // so we need to check if there's any socks5Setting, when there is none,
                // we just think of does not need auth
                if (!authed || (inbound.socks5Settings.isNotEmpty() && inbound.socks5Settings.none { it.auth == null })) {
                    ctx.close()
                }
                if (socksRequest.type() === Socks5CommandType.CONNECT) {
                    ctx.pipeline().addLast(SocksServerConnectHandler(inbound, socks5Setting))
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
        //if there is any auth configuration in inbound setting then do auth
        if (inbound.socks5Settings.any { it.auth != null }) {
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
        inbound.socks5Settings.filter {
            it.auth?.username != socksRequest.username() || it.auth?.password != socksRequest.password()
        }.first {
            ctx.pipeline().addFirst(Socks5CommandRequestDecoder())
            ctx.write(DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS))
            authed = true
            socks5Setting = it
            return@socks5DoAuth
        }
        logger.warn {
            "[${ctx.channel().id().asShortText()}] socks5 auth failed from: ${
                ctx.channel().remoteAddress()
            }"
        }
        ctx.write(DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
        ctx.close()
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) { cause.message }
        ctx.channel().closeOnFlush()
    }
}

@Sharable
class SocksServerConnectHandler(private val inbound: Inbound, private val socks5Setting: Socks5Setting?) :
    SimpleChannelInboundHandler<SocksMessage>() {


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
        val resolveOutbound = resolveOutbound(socks5Setting?.tag ?: inbound.tag, odor)
        logger.info {
            "[${
                originCTX.channel().id().asShortText()
            }] socks5 inbounded, uri: ${message.dstAddr()}:${message.dstPort()}, command: ${message.type()}"
        }

        resolveOutbound.ifPresent { outbound ->

            relayAndOutbound(RelayAndOutboundOp(
                originCTX = originCTX, outbound = outbound, odor = odor
            ).also { relayAndOutboundOp ->
                relayAndOutboundOp.connectEstablishedCallback = {c,f->
                    originCTX.channel().writeAndFlush(
                        DefaultSocks5CommandResponse(
                            Socks5CommandStatus.SUCCESS, message.dstAddrType(), message.dstAddr(), message.dstPort()
                        )
                    ).addListener(ChannelFutureListener {
                        originCTX.pipeline().remove(this@SocksServerConnectHandler)
                        f()
                    })
                }
                relayAndOutboundOp.connectFail = {
                    originCTX.close()
                }
            })
        }

    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) { cause.message }
        ctx.channel().closeOnFlush()
    }
}
