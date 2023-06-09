package stream


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.ProxyConnectionEvent
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.IdleStateHandler
import io.netty.resolver.NoopAddressResolverGroup
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.PROXY_HANDLER_NAME
import model.RELAY_HANDLER_NAME
import model.config.*
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import netty.IdleCloseHandler
import protocol.DiscardHandler
import protocol.TrojanRelayInboundHandler
import utils.ChannelUtils
import java.net.InetSocketAddress
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Handle all incoming messages, and relay them to the outbound
 */
fun union(relayAndOutboundOp: RelayAndOutboundOp, inbound: Inbound, odor: Odor) {
//    val outboundOptional = resolveOutbound(inbound, odor)
//
//    if (outboundOptional.isPresent) {
//        resolveOutbound(relayAndOutboundOp)
//    }else{
//        logger.error { "outbound not found" }
//    }
}

/**
 * Commonly used relay and outbound methods
 * @param relayAndOutboundOp [RelayAndOutboundOp]
 */
fun relayAndOutbound(relayAndOutboundOp: RelayAndOutboundOp) {
    if (!relayAndOutboundOp.overrideRelayHandler) {
        when (relayAndOutboundOp.outbound.protocol) {
            "galaxy" -> {
                //ignored
            }

            "trojan" -> {
                relayAndOutboundOp.originCTXRelayHandler = {
                    TrojanRelayInboundHandler(
                        it,
                        relayAndOutboundOp.outbound,
                        relayAndOutboundOp.odor
                    )
                }
            }

            else -> {
                logger.error { "protocol ${relayAndOutboundOp.outbound.protocol} not supported" }
                relayAndOutboundOp.connectFail()
            }
        }
    }
    relayAndOutbound(
        originCTX = relayAndOutboundOp.originCTX,
        originCTXRelayHandler = relayAndOutboundOp.originCTXRelayHandler,
        outbound = relayAndOutboundOp.outbound,
        connectEstablishedCallback = relayAndOutboundOp.connectEstablishedCallback,
        afterAddRelayHandler = relayAndOutboundOp.afterAddRelayHandler,
        connectFail = relayAndOutboundOp.connectFail,
        odor = relayAndOutboundOp.odor
    )
}

fun relayAndOutbound(
    originCTX: ChannelHandlerContext,
    originCTXRelayHandler: (Channel) -> RelayInboundHandler = { RelayInboundHandler(it) },
    outbound: Outbound,
    connectEstablishedCallback: (Channel) -> ChannelFuture,
    afterAddRelayHandler: (Channel) -> Unit = {},
    connectFail: () -> Unit = {},
    odor: Odor
) {

    val connectListener = FutureListener<Channel> { future ->
        val outboundChannel = future.now
        if (future.isSuccess) {
            logger.trace { "outboundChannel: $outboundChannel" }
            connectEstablishedCallback(outboundChannel).addListener(FutureListener {
                if (it.isSuccess) {
                    outboundChannel.pipeline().addLast(RELAY_HANDLER_NAME, RelayInboundHandler(originCTX.channel()))
                    originCTX.pipeline().addLast(RELAY_HANDLER_NAME, originCTXRelayHandler(outboundChannel))
                    afterAddRelayHandler(outboundChannel)
                } else {
                    logger.error(it.cause()) { "connectEstablishedCallback fail: ${it.cause().message}" }
                }
            })
        } else {
            logger.error(future.cause().message)
            logger.debug { future.cause().stackTrace }
            connectFail()
        }
    }
    outbound(
        outbound = outbound,
        connectListener = connectListener,
        eventLoopGroup = originCTX.channel().eventLoop(),
        odor = odor
    )
}

/**
 * relay and outbound
 */

private fun outbound(
    outbound: Outbound,
    connectListener: FutureListener<Channel>,
    odor: Odor,
    eventLoopGroup: EventLoopGroup = NioEventLoopGroup()
) {
    if (outbound.outboundStreamBy == null) {
        return galaxy(connectListener, odor, eventLoopGroup)
    }
    return when (Protocol.valueOfOrNull(outbound.outboundStreamBy.type)) {
        Protocol.WSS -> wssStream(
            connectListener, outbound.outboundStreamBy.wsOutboundSetting!!, eventLoopGroup, odor
        )

        Protocol.WS -> wsStream(
            connectListener, outbound.outboundStreamBy.wsOutboundSetting!!, eventLoopGroup, odor
        )

        Protocol.SOCKS5 -> socks5Stream(
            connectListener, outbound.outboundStreamBy.sock5OutboundSetting!!, eventLoopGroup, odor
        )

        Protocol.HTTP -> httpStream(
            connectListener, outbound.outboundStreamBy.httpOutboundSetting!!, eventLoopGroup, odor
        )

        Protocol.TCP -> tcpStream(
            connectListener, outbound.outboundStreamBy.tcpOutboundSetting!!, eventLoopGroup, odor
        )

        Protocol.TLS -> tlsStream(
            connectListener, outbound.outboundStreamBy.tcpOutboundSetting!!, eventLoopGroup, odor
        )

        else -> {
            logger.error { "stream type ${outbound.outboundStreamBy.type} not supported" }
        }
    }
}

private fun tlsStream(
    connectListener: FutureListener<Channel>,
    tcpOutboundSetting: TcpOutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    odor.redirectPort = tcpOutboundSetting.port
    odor.redirectHost = tcpOutboundSetting.host
    val promise = eventLoopGroup.next().newPromise<Channel>()
    promise.addListener(connectListener)
    val sslCtx: SslContext =
        SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    connect(
        eventLoopGroup, {
            mutableListOf(
                HandlerPair(sslCtx.newHandler(it.alloc(), tcpOutboundSetting.host, tcpOutboundSetting.port)),
                HandlerPair(ChannelActiveHandler(promise)),
            )
        }, odor
    )
}

private fun tcpStream(
    connectListener: FutureListener<Channel>,
    tcpOutboundSetting: TcpOutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    odor.redirectPort = tcpOutboundSetting.port
    odor.redirectHost = tcpOutboundSetting.host
    val promise = eventLoopGroup.next().newPromise<Channel>()
    promise.addListener(connectListener)

    connect(
        eventLoopGroup, {
            mutableListOf(
                HandlerPair(ChannelActiveHandler(promise))
            )
        }, odor
    )
}

private fun wssStream(
    connectListener: FutureListener<Channel>,
    wsOutboundSetting: WsOutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    odor.redirectPort = wsOutboundSetting.port
    odor.redirectHost = wsOutboundSetting.host
    val promise = eventLoopGroup.next().newPromise<Channel>()
    promise.addListener(connectListener)

    val uri =
        URI("wss://${wsOutboundSetting.host}:${wsOutboundSetting.port}/${wsOutboundSetting.path.removePrefix("/")}")

    val sslCtx: SslContext =
        SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()

    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(sslCtx.newHandler(it.alloc(), wsOutboundSetting.host, wsOutboundSetting.port)),
            HandlerPair(HttpClientCodec()),
            HandlerPair(HttpObjectAggregator(8192)),
            HandlerPair(WebSocketClientCompressionHandler.INSTANCE),
            HandlerPair(
                WebSocketClientProtocolHandler(
                    WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
                    )
                )
            ),
            HandlerPair(WebsocketDuplexHandler(promise))
        )
    }, odor)
}

private fun wsStream(
    connectListener: FutureListener<Channel>,
    wsOutboundSetting: WsOutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    odor.redirectHost = wsOutboundSetting.host
    odor.redirectPort = wsOutboundSetting.port
    val promise = eventLoopGroup.next().newPromise<Channel>()
    promise.addListener(connectListener)

    val uri =
        URI("ws://${wsOutboundSetting.host}:${wsOutboundSetting.port}/${wsOutboundSetting.path.removePrefix("/")}")
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(HttpClientCodec()),
            HandlerPair(HttpObjectAggregator(8192)),
            HandlerPair(WebSocketClientCompressionHandler.INSTANCE),
            HandlerPair(
                WebSocketClientProtocolHandler(
                    WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
                    )
                )
            ),
            HandlerPair(WebsocketDuplexHandler(promise))
        )
    }, odor)

}


private fun galaxy(
    connectListener: FutureListener<Channel>, odor: Odor, eventLoopGroup: EventLoopGroup
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(ChannelActiveHandler(promise))
        )
    }, odor)
}

private fun httpStream(
    connectListener: FutureListener<Channel>,
    httpOutboundSetting: HttpOutboundSetting,
    eventLoopGroup: EventLoopGroup,
    socketAddress: Odor
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    val proxyHandler = if (httpOutboundSetting.auth == null) {
        HttpProxyHandler(
            InetSocketAddress(
                httpOutboundSetting.host, httpOutboundSetting.port
            )
        )
    } else {
        HttpProxyHandler(
            InetSocketAddress(httpOutboundSetting.host, httpOutboundSetting.port),
            httpOutboundSetting.auth.username,
            httpOutboundSetting.auth.password
        )
    }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(proxyHandler, PROXY_HANDLER_NAME),
            HandlerPair(ChannelActiveHandler(promise))
        )
    }, socketAddress)
}

private fun socks5Stream(
    connectListener: FutureListener<Channel>,
    socks5OutboundSetting: Sock5OutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    val proxyHandler = if (socks5OutboundSetting.auth == null) {
        Socks5ProxyHandler(
            InetSocketAddress(
                socks5OutboundSetting.host, socks5OutboundSetting.port
            )
        )
    } else {
        Socks5ProxyHandler(
            InetSocketAddress(socks5OutboundSetting.host, socks5OutboundSetting.port),
            socks5OutboundSetting.auth.username,
            socks5OutboundSetting.auth.password
        )
    }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(proxyHandler, PROXY_HANDLER_NAME),
            HandlerPair(ChannelActiveHandler(promise))
        )
    }, odor)
}

private fun connect(
    eventLoopGroup: EventLoopGroup,
    handlerPairs: (Channel) -> MutableList<HandlerPair>,
    odor: Odor
) {
    val protocol = odor.desProtocol.topProtocol()
    if (protocol == Protocol.TCP) {
        connectTcp(eventLoopGroup, SurferInitializer(handlerPairs), odor.socketAddress())
    } else if (protocol == Protocol.UDP) {
        connectUdp(eventLoopGroup, SurferInitializer(handlerPairs), odor.socketAddress())
    }

}

class SurferInitializer(private val handlerPairs: (Channel) -> MutableList<HandlerPair>) :
    ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IdleStateHandler(300, 300, 300))
        ch.pipeline().addFirst(IdleCloseHandler())
        handlerPairs(ch).forEach {
            if (it.name != null) {
                ch.pipeline().addLast(it.name, it.handler)
            } else {
                ch.pipeline().addLast(it.handler)
            }
        }

    }
}

private fun connectTcp(
    eventLoopGroup: EventLoopGroup,
    channelInitializer: ChannelInitializer<Channel>,
    socketAddress: InetSocketAddress
) {
    Bootstrap().group(eventLoopGroup)
        .resolver(NoopAddressResolverGroup.INSTANCE)
        .channel(NioSocketChannel::class.java)
        .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
        .handler(channelInitializer)
        .connect(socketAddress)
}

private fun connectUdp(
    eventLoopGroup: EventLoopGroup,
    channelInitializer: ChannelInitializer<Channel>,
    socketAddress: InetSocketAddress
) {
    Bootstrap().group(eventLoopGroup)
        .channel(NioDatagramChannel::class.java)
        .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
        .handler(channelInitializer)
        .connect(socketAddress)
}

/**
 * basic activator for client connected, some protocol not have complex handshake process, so we can use this activator to active other operation
 */
class ChannelActiveHandler(private val promise: Promise<Channel>) : ChannelDuplexHandler() {
    private val logger = KotlinLogging.logger {}
    override fun channelActive(ctx: ChannelHandlerContext) {
        super.channelActive(ctx)
        promise.setSuccess(ctx.channel())
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is ProxyConnectionEvent) {
            logger.trace { "ProxyConnectionEvent: $evt" }
        }
        ctx.fireUserEventTriggered(evt)
    }
}


/**
 * relay from client channel to server
 */
open class RelayInboundHandler(private val relayChannel: Channel, private val inActiveCallBack: () -> Unit = {}) :
    ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (relayChannel.isActive) {
            logger.trace(
                "relay inbound read from [{}] pipeline handlers:{}, to [{}] pipeline handlers:{}, write message:{}",
                ctx.channel().id().asShortText(),
                ctx.channel().pipeline().names(),
                relayChannel.id().asShortText(),
                relayChannel.pipeline().names(),
                msg.javaClass.name
            )
            relayChannel.writeAndFlush(msg).addListener(ChannelFutureListener {
                if (!it.isSuccess) {
                    logger.error(
                        "relay inbound write message:${msg.javaClass.name} to [${
                            relayChannel.id().asShortText()
                        }] failed, cause: ${it.cause().message}", it.cause()
                    )
                }
            })
        } else {
            logger.warn(
                "[${
                    relayChannel.id().asShortText()
                }] relay channel is not active, close message:${msg.javaClass.name}"
            )
            ReferenceCountUtil.release(msg)
            ChannelUtils.closeOnFlush(ctx.channel())
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (relayChannel.isActive) {
            logger.debug(
                "[{}] closed channel, write close to relay channel [{}]",
                ctx.channel().id().asShortText(),
                relayChannel.id().asShortText()
            )
            relayChannel.pipeline().remove(RELAY_HANDLER_NAME)
            //add a discard handler to discard all message
            relayChannel.pipeline().addLast(DiscardHandler())
            relayChannel.close()
            inActiveCallBack()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(
            "relay inbound handler exception caught, [${ctx.channel().id()}], pipeline: ${
                ctx.channel().pipeline().names()
            }, message: ${cause.message}", cause
        )
        ctx.close()
    }
}

data class HandlerPair(val handler: ChannelHandler, val name: String?) {
    constructor(handler: ChannelHandler) : this(handler, null)
}
