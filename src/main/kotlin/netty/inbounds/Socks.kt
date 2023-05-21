package netty.inbounds


import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v5.*
import model.config.Inbound
import mu.KotlinLogging
import netty.outbounds.GalaxyOutbound
import netty.outbounds.Trojan
import utils.ChannelUtils
import utils.SurferUtils.resolveOutbound

@Sharable
class SocksServerHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<SocksMessage>() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

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
    private fun socks5Connect(
        ctx: ChannelHandlerContext, socksRequest: SocksMessage
    ) {
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
            ctx.pipeline().addFirst(Socks5CommandRequestDecoder())
            ctx.write(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
        }
    }

    /**
     * socks5 auth
     * if there's auth configuration in inbound setting then do auth
     */
    private fun socks5DoAuth(
        socksRequest: Socks5PasswordAuthRequest, ctx: ChannelHandlerContext
    ) {
        if (inbound.socks5Setting?.auth?.username != socksRequest.username() || inbound.socks5Setting?.auth?.password != socksRequest.password()) {
            logger.warn("socks5 auth failed from: ${ctx.channel().remoteAddress()}")
            ctx.write(DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
            ctx.close()
            return
        }
        ctx.pipeline().addFirst(Socks5CommandRequestDecoder())
        ctx.write(DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS))
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
class SocksServerConnectHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<SocksMessage?>() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    public override fun channelRead0(originCTX: ChannelHandlerContext, message: SocksMessage?) {
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
    private fun socks5Command(
        originCTX: ChannelHandlerContext, message: Socks5CommandRequest
    ) {
        val resolveOutbound = resolveOutbound(inbound)
        logger.info(
            "socks5 inbound: [{}],uri: {}",
            originCTX.channel().id().asShortText(),
            "${message.dstAddr()}:${message.dstPort()}"
        )
        resolveOutbound.ifPresent { outbound ->
            when (outbound.protocol) {
                "galaxy" -> {
                    GalaxyOutbound.outbound(originCTX, outbound, message.dstAddr(), message.dstPort(), {
                        originCTX.channel().writeAndFlush(
                            DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS, message.dstAddrType(), message.dstAddr(), message.dstPort()
                            )
                        ).addListener(ChannelFutureListener {
                            originCTX.pipeline().remove(this@SocksServerConnectHandler)
                        })
                    }, {
                        //while connect failed, write failure response to client, and close the connection
                        originCTX.channel().writeAndFlush(
                            DefaultSocks5CommandResponse(
                                Socks5CommandStatus.FAILURE, message.dstAddrType()
                            )
                        )
                        ChannelUtils.closeOnFlush(originCTX.channel())
                    })
                }

                "trojan" -> {
                    Trojan.outbound(originCTX,
                        outbound,
                        message.dstAddrType().byteValue(),
                        message.dstAddr(),
                        message.dstPort(),
                        {
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
                        },
                        {
                            //while connect failed, write failure response to client, and close the connection
                            originCTX.channel().writeAndFlush(
                                DefaultSocks5CommandResponse(
                                    Socks5CommandStatus.FAILURE, message.dstAddrType()
                                )
                            )
                            ChannelUtils.closeOnFlush(originCTX.channel())
                        })
                }

                else -> {
                    logger.error(
                        "id: ${
                            originCTX.channel().id().asShortText()
                        }, protocol=${outbound.protocol} not support"
                    )
                }
            }
        }

    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause.message, cause)
        ChannelUtils.closeOnFlush(ctx.channel())
    }
}
