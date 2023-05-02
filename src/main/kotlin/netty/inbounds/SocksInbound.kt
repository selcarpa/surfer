package netty.inbounds


import io.netty.channel.*
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.socksx.SocksMessage
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v4.DefaultSocks4CommandResponse
import io.netty.handler.codec.socksx.v4.Socks4CommandRequest
import io.netty.handler.codec.socksx.v4.Socks4CommandStatus
import io.netty.handler.codec.socksx.v4.Socks4CommandType
import io.netty.handler.codec.socksx.v5.*
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.FutureListener
import model.config.Inbound
import model.protocol.TrojanRequest
import mu.KotlinLogging
import netty.outbounds.TrojanOutbound
import netty.outbounds.TrojanRelayInboundHandler
import netty.stream.PromiseHandler
import netty.stream.RelayInboundHandler
import netty.stream.StreamFactory
import utils.ChannelUtils
import utils.EasyPUtils.resolveOutbound
import java.lang.Exception

@Sharable
class SocksServerHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<SocksMessage>() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    public override fun channelRead0(ctx: ChannelHandlerContext, socksRequest: SocksMessage) {
        when (socksRequest.version()!!) {
            SocksVersion.SOCKS4a -> socks4Connect(ctx, socksRequest)
            SocksVersion.SOCKS5 -> socks5Connect(ctx, socksRequest)
            SocksVersion.UNKNOWN -> ctx.close()
        }
    }

    /**
     * socks4 connect
     */
    private fun socks4Connect(
        ctx: ChannelHandlerContext, socksRequest: SocksMessage
    ) {
        if (inbound.protocol != "socks4" && inbound.protocol != "socks4a") {
            ctx.close()
            return
        }
        val socksV4CmdRequest = socksRequest as Socks4CommandRequest
        if (socksV4CmdRequest.type() === Socks4CommandType.CONNECT) {
            ctx.pipeline().addLast(SocksServerConnectHandler(inbound))
            ctx.pipeline().remove(this)
            ctx.fireChannelRead(socksRequest)
        } else {
            ctx.close()
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
        if (socksRequest is Socks5InitialRequest) {
            socks5auth(ctx)
        } else if (socksRequest is Socks5PasswordAuthRequest) {
            socks5DoAuth(socksRequest, ctx)
        } else if (socksRequest is Socks5CommandRequest) {
            if (socksRequest.type() === Socks5CommandType.CONNECT) {
                ctx.pipeline().addLast(SocksServerConnectHandler(inbound))
                ctx.pipeline().remove(this)
                ctx.fireChannelRead(socksRequest)
            } else {
                ctx.close()
            }
        } else {
            ctx.close()
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
            is Socks4CommandRequest -> socks4Command(originCTX, message)
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
        resolveOutbound.ifPresent { outbound ->
            when (outbound.protocol) {
                "galaxy" -> {}
                "trojan" -> {
                    StreamFactory.getStream(
                        outbound.outboundStreamBy
                    ).addListener(object : FutureListener<Channel?> {
                        override fun operationComplete(future: Future<Channel?>) {
                            val outboundChannel = future.now!!
                            if (future.isSuccess) {
                                val responseFuture = originCTX.channel().writeAndFlush(
                                    DefaultSocks5CommandResponse(
                                        Socks5CommandStatus.SUCCESS,
                                        message.dstAddrType(),
                                        message.dstAddr(),
                                        message.dstPort()
                                    )
                                )
                                responseFuture.addListener(object : ChannelFutureListener {
                                    override fun operationComplete(channelFuture: ChannelFuture) {
//                                        originCTX.pipeline().remove(this@SocksServerConnectHandler)
                                        outboundChannel.pipeline().addLast(
                                            TrojanOutbound(), RelayInboundHandler(originCTX.channel()),
                                        )
                                        originCTX.pipeline().addLast(
                                            TrojanRelayInboundHandler(
                                                outboundChannel, outbound.trojanSetting!!, TrojanRequest(
                                                    Socks5CommandType.CONNECT,
                                                    message.dstAddrType(),
                                                    message.dstAddr(),
                                                    message.dstPort()
                                                )
                                            ),
                                        )
                                    }
                                })
                            } else {
                                //while connect failed, write failure response to client, and close the connection
                                originCTX.channel().writeAndFlush(
                                    DefaultSocks5CommandResponse(
                                        Socks5CommandStatus.FAILURE, message.dstAddrType()
                                    )
                                )
                                ChannelUtils.closeOnFlush(originCTX.channel())
                            }
                        }
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

    private fun socks4Command(
        originCTX: ChannelHandlerContext, message: Socks4CommandRequest
    ) {
        val promise = originCTX.executor().newPromise<Channel>()
        promise.addListener(object : FutureListener<Channel?> {
            override fun operationComplete(future: Future<Channel?>) {
                val outboundChannel = future.now
                if (future.isSuccess) {
                    val responseFuture = originCTX.channel().writeAndFlush(
                        DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS)
                    )
                    responseFuture.addListener(ChannelFutureListener {
                        originCTX.pipeline().remove(this@SocksServerConnectHandler)
                        outboundChannel?.pipeline()?.addLast(RelayInboundHandler(originCTX.channel()))
                        originCTX.pipeline().addLast(outboundChannel?.let { it1 -> RelayInboundHandler(it1) })
                    })
                } else {
                    originCTX.channel().writeAndFlush(
                        DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)
                    )
                    ChannelUtils.closeOnFlush(originCTX.channel())
                }
            }
        })
        val inboundChannel = originCTX.channel()
//        b.group(inboundChannel.eventLoop()).channel(NioSocketChannel::class.java)
//            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).option(ChannelOption.SO_KEEPALIVE, true)
//            .handler(PromiseHandler(promise))
//        b.connect(message.dstAddr(), message.dstPort()).addListener(object : ChannelFutureListener {
//            @Throws(Exception::class)
//            override fun operationComplete(future: ChannelFuture) {
//                if (future.isSuccess) {
//                    // Connection established use handler provided results
//                } else {
//                    // Close the connection if the connection attempt has failed.
//                    originCTX.channel().writeAndFlush(
//                        DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED)
//                    )
//                    ChannelUtils.closeOnFlush(originCTX.channel())
//                }
//            }
//        })
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause.message, cause)
        ChannelUtils.closeOnFlush(ctx.channel())
    }
}

