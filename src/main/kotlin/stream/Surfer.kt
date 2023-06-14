package stream


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
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
import io.netty.handler.ssl.SslCompletionEvent
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.PROXY_HANDLER_NAME
import model.RELAY_HANDLER_NAME
import model.config.*
import model.protocol.ConnectTo
import mu.KotlinLogging
import protocol.DiscardHandler
import utils.ChannelUtils
import java.net.InetSocketAddress
import java.net.URI


object Surfer {

    private val logger = KotlinLogging.logger {}

    fun relayAndOutbound(
        originCTX: ChannelHandlerContext,
        originCTXRelayHandler: (Channel) -> RelayInboundHandler = { RelayInboundHandler(it) },
        outbound: Outbound,
        connectEstablishedCallback: (Channel) -> ChannelFuture,
        afterAddRelayHandler: (Channel) -> Unit = {},
        connectFail: () -> Unit = {},
        connectTo: ConnectTo
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
                connectFail()
            }
        }
        outbound(
            outbound = outbound,
            connectListener = connectListener,
            eventLoopGroup = originCTX.channel().eventLoop(),
            socketAddress = connectTo.socketAddress()

        )
    }

    private fun outbound(
        outbound: Outbound,
        connectListener: FutureListener<Channel>,
        socketAddress: InetSocketAddress,
        eventLoopGroup: EventLoopGroup = NioEventLoopGroup()
    ) {
        if (outbound.outboundStreamBy == null) {
            return galaxy(connectListener, socketAddress, eventLoopGroup)
        }
        return when (outbound.outboundStreamBy.type) {
            "wss" -> wssStream(
                connectListener, outbound.outboundStreamBy.wsOutboundSetting!!, eventLoopGroup
            )

            "ws" -> wsStream(
                connectListener, outbound.outboundStreamBy.wsOutboundSetting!!, eventLoopGroup
            )

            "socks5" -> socks5Stream(
                connectListener, outbound.outboundStreamBy.sock5OutboundSetting!!, eventLoopGroup, socketAddress
            )

            "http" -> httpStream(
                connectListener, outbound.outboundStreamBy.httpOutboundSetting!!, eventLoopGroup, socketAddress
            )

            "tcp" -> tcpStream(
                connectListener, outbound.outboundStreamBy.tcpOutboundSetting!!, eventLoopGroup
            )

            "tls" -> tlsStream(
                connectListener, outbound.outboundStreamBy.tcpOutboundSetting!!, eventLoopGroup
            )


            else -> {
                logger.error { "stream type ${outbound.outboundStreamBy.type} not supported" }
            }
        }
    }

    private fun tlsStream(
        connectListener: FutureListener<Channel>,
        tcpOutboundSetting: TcpOutboundSetting,
        eventLoopGroup: EventLoopGroup
    ) {
        val promise = eventLoopGroup.next().newPromise<Channel>()
        promise.addListener(connectListener)
        val sslCtx: SslContext =
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
        connect(
            eventLoopGroup, {
                mutableListOf(
                    HandlerPair(SslActiveHandler(promise)),
                    HandlerPair(sslCtx.newHandler(it.alloc(), tcpOutboundSetting.host, tcpOutboundSetting.port))

                )
            }, InetSocketAddress(tcpOutboundSetting.host, tcpOutboundSetting.port)
        )
    }

    private fun tcpStream(
        connectListener: FutureListener<Channel>,
        tcpOutboundSetting: TcpOutboundSetting,
        eventLoopGroup: EventLoopGroup
    ) {
        val promise = eventLoopGroup.next().newPromise<Channel>()
        promise.addListener(connectListener)

        connect(
            eventLoopGroup, {
                mutableListOf(
                    HandlerPair(ChannelActiveHandler(promise))
                )
            }, InetSocketAddress(tcpOutboundSetting.host, tcpOutboundSetting.port)
        )
    }

    private fun wssStream(
        connectListener: FutureListener<Channel>,
        wsOutboundSetting: WsOutboundSetting,
        eventLoopGroup: EventLoopGroup
    ) {
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
        }, InetSocketAddress(uri.host, uri.port))
    }

    private fun wsStream(
        connectListener: FutureListener<Channel>,
        wsOutboundSetting: WsOutboundSetting,
        eventLoopGroup: EventLoopGroup
    ) {
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
        }, InetSocketAddress(uri.host, uri.port))

    }


    private fun galaxy(
        connectListener: FutureListener<Channel>, socketAddress: InetSocketAddress, eventLoopGroup: EventLoopGroup
    ) {
        val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
        connect(eventLoopGroup, {
            mutableListOf(
                HandlerPair(ChannelActiveHandler(promise))
            )
        }, socketAddress)
    }

    private fun httpStream(
        connectListener: FutureListener<Channel>,
        httpOutboundSetting: HttpOutboundSetting,
        eventLoopGroup: EventLoopGroup,
        socketAddress: InetSocketAddress
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
        socketAddress: InetSocketAddress
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
        }, socketAddress)
    }

    private fun connect(
        eventLoopGroup: EventLoopGroup,
        handlerInitializer: (Channel) -> MutableList<HandlerPair>,
        inetSocketAddress: InetSocketAddress
    ) {
        connect(eventLoopGroup, object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                handlerInitializer(ch).forEach {
                    if (it.name != null) {
                        ch.pipeline().addLast(it.name, it.handler)
                    } else {
                        ch.pipeline().addLast(it.handler)
                    }
                }
            }
        }, inetSocketAddress)

    }

    private fun connect(
        eventLoopGroup: EventLoopGroup,
        channelInitializer: ChannelInitializer<Channel>,
        socketAddress: InetSocketAddress
    ) {
        Bootstrap().group(eventLoopGroup).channel(NioSocketChannel::class.java)
            .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
            .handler(channelInitializer)
            .connect(socketAddress)
    }
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
 * ssl activator for client connected, when ssl handshake complete, we can active other operation
 */
class SslActiveHandler(private val promise: Promise<Channel>) : ChannelDuplexHandler() {
    private val logger = KotlinLogging.logger {}
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is SslCompletionEvent) {
            logger.trace { "SslCompletionEvent: $evt" }
            promise.setSuccess(ctx.channel())
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
                        }] failed, cause: {}", it.cause()
                    )
                    logger.error(it.cause().message, it.cause())
                }
            })
        } else {
            logger.warn(
                "relay channel　[${
                    relayChannel.id().asShortText()
                }] is not active, close message:${msg.javaClass.name}"
            )
            ReferenceCountUtil.release(msg)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (relayChannel.isActive) {
            logger.debug("[{}]  close channel, write close to relay channel", relayChannel.id().asShortText())
            relayChannel.pipeline().remove(RELAY_HANDLER_NAME)
            //add a discard handler to discard all message
            relayChannel.pipeline().addLast(DiscardHandler())
            ChannelUtils.closeOnFlush(relayChannel)
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

