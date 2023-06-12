package stream


import inbounds.Websocket
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.ProxyConnectionEvent
import io.netty.handler.proxy.ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.PROXY_HANDLER_NAME
import model.RELAY_HANDLER_NAME
import model.config.HttpOutboundSetting
import model.config.Outbound
import model.config.Sock5OutboundSetting
import model.config.WsOutboundSetting
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
                connectListener, outbound.outboundStreamBy.wsOutboundSetting!!, eventLoopGroup, socketAddress
            )

            "ws" -> wsStream(
                connectListener, outbound.outboundStreamBy.wsOutboundSetting!!, eventLoopGroup, socketAddress
            )

            "socks5" -> socks5Stream(
                connectListener, outbound.outboundStreamBy.sock5OutboundSetting!!, eventLoopGroup, socketAddress
            )

            "http" -> httpStream(
                connectListener, outbound.outboundStreamBy.httpOutboundSetting!!, eventLoopGroup, socketAddress
            )

            else -> {
                logger.error { "stream type ${outbound.outboundStreamBy.type} not supported" }
            }
        }
    }

    private fun wssStream(
        connectListener: FutureListener<Channel>,
        wsOutboundSetting: WsOutboundSetting,
        eventLoopGroup: EventLoopGroup,
        socketAddress: InetSocketAddress
    ) {
        val promise = eventLoopGroup.next().newPromise<Channel>()
        promise.addListener(connectListener)

        val uri =
            URI(
                "wss://${wsOutboundSetting.host}:${if (wsOutboundSetting.port != null) wsOutboundSetting.port else 443}/${
                    wsOutboundSetting.path.removePrefix(
                        "/"
                    )
                }"
            )

        val sslCtx: SslContext =
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()


        // Connect with V13 (RFC 6455 aka HyBi-17). You can change it to V08 or V00.
        // If you change it to V00, ping is not supported and remember to change
        // HttpResponseDecoder to WebSocketHttpResponseDecoder in the pipeline.
        val webSocketClientHandler = WebSocketClientHandler(
            WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
            ), promise
        )

        connect(eventLoopGroup, object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(
                    sslCtx.newHandler(ch.alloc(), wsOutboundSetting.host, wsOutboundSetting.port),
                    HttpClientCodec(),
                    HttpObjectAggregator(8192),
                    WebSocketClientCompressionHandler.INSTANCE,
                    webSocketClientHandler
                )
            }

        }, InetSocketAddress(uri.host, uri.port))
        connect(eventLoopGroup, {
            mutableListOf(
                sslCtx.newHandler(it.alloc(), wsOutboundSetting.host, wsOutboundSetting.port),
                HttpClientCodec(),
                HttpObjectAggregator(8192),
                WebSocketClientCompressionHandler.INSTANCE,
                webSocketClientHandler
            )
        }, InetSocketAddress(uri.host, uri.port))
    }

    private fun wsStream(
        connectListener: FutureListener<Channel>,
        wsOutboundSetting: WsOutboundSetting,
        eventLoopGroup: EventLoopGroup,
        socketAddress: InetSocketAddress
    ) {
        val promise = eventLoopGroup.next().newPromise<Channel>()
        promise.addListener(connectListener)

        val uri =
            URI("ws://${wsOutboundSetting.host}:${if (wsOutboundSetting.port != null) wsOutboundSetting.port else 80}/${wsOutboundSetting.path}")
        // Connect with V13 (RFC 6455 aka HyBi-17). You can change it to V08 or V00.
        // If you change it to V00, ping is not supported and remember to change
        // HttpResponseDecoder to WebSocketHttpResponseDecoder in the pipeline.
        val webSocketClientHandler = WebSocketClientHandler(
            WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
            ), promise
        )
        connect(eventLoopGroup, {
            mutableListOf(
                HttpClientCodec(),
                HttpObjectAggregator(8192),
                WebSocketClientCompressionHandler.INSTANCE,
                webSocketClientHandler
            )
        }, InetSocketAddress(uri.host, uri.port))


    }


    private fun galaxy(
        connectListener: FutureListener<Channel>, socketAddress: InetSocketAddress, eventLoopGroup: EventLoopGroup
    ) {
        connect(eventLoopGroup, connectListener, null, socketAddress)
    }

    private fun httpStream(
        connectListener: FutureListener<Channel>,
        httpOutboundSetting: HttpOutboundSetting,
        eventLoopGroup: EventLoopGroup,
        socketAddress: InetSocketAddress
    ) {
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
        connect(eventLoopGroup, connectListener, proxyHandler, socketAddress)
    }

    private fun socks5Stream(
        connectListener: FutureListener<Channel>,
        socks5OutboundSetting: Sock5OutboundSetting,
        eventLoopGroup: EventLoopGroup,
        socketAddress: InetSocketAddress
    ) {
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
        connect(eventLoopGroup, connectListener, proxyHandler, socketAddress)
    }

    private fun connect(
        eventLoopGroup: EventLoopGroup,
        handlerInitializer: (Channel) -> MutableList<ChannelHandler>,
        inetSocketAddress: InetSocketAddress
    ) {
        connect(eventLoopGroup, object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                handlerInitializer(ch).forEach {
                    ch.pipeline().addLast(it)
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

    private fun connect(
        eventLoopGroup: EventLoopGroup,
        connectListener: FutureListener<Channel>,
        proxyHandler: ProxyHandler?,
        socketAddress: InetSocketAddress
    ) {
        val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
        Bootstrap().group(eventLoopGroup).channel(NioSocketChannel::class.java)
            .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    if (proxyHandler != null) {
                        ch.pipeline().addLast(
                            PROXY_HANDLER_NAME, proxyHandler
                        )
                    }
                    ch.pipeline().addLast(ChannelActiveHandler(promise))
                }

            }).connect(socketAddress)
    }
}

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

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        logger.trace(
            "[{}], galaxy read {}, pipelines: {}",
            ctx.channel().id().asShortText(),
            msg,
            ctx.channel().pipeline().names()
        )
        super.channelRead(ctx, msg)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        logger.error(cause) { "proxy exceptionCaught" }
    }
}


class WebSocketClientHandler(
    private val handshaker: WebSocketClientHandshaker, private val connectPromise: Promise<Channel>
) : ChannelDuplexHandler() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        handshaker.handshake(ctx.channel())
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val ch = ctx.channel()
        if (!handshaker.isHandshakeComplete) {
            try {
                handshaker.finishHandshake(ch, msg as FullHttpResponse?)
                logger.trace("[${ctx.channel().id().asShortText()}] WebSocket Client connected!")
                connectPromise.setSuccess(ctx.channel())
            } catch (e: WebSocketHandshakeException) {
                logger.debug("WebSocket Client failed to connect")
                connectPromise.setFailure(e)
            }
            return
        }
        when (msg) {
            is FullHttpResponse -> {
                ReferenceCountUtil.release(msg)
                error(
                    "Unexpected FullHttpResponse (getStatus=${msg.status()}, content=${
                        msg.content().toString(CharsetUtil.UTF_8)
                    })"
                )
            }

            is TextWebSocketFrame -> {
                logger.trace(
                    "[${
                        ctx.channel().id().asShortText()
                    }], WebSocket Client received message: " + msg.text()
                )
                ctx.fireChannelRead(msg)
            }

            is PongWebSocketFrame -> {
                logger.debug("[${ctx.channel().id().asShortText()}], WebSocket Client received pong")
            }

            is CloseWebSocketFrame -> {
                logger.debug("[${ctx.channel().id().asShortText()}], WebSocket Client received closing")
                ch.close()
            }

            is BinaryWebSocketFrame -> {
                logger.trace(
                    "[${
                        ctx.channel().id().asShortText()
                    }], WebSocket Client receive message:{}, pipeline handlers:{}",
                    msg.javaClass.name,
                    ctx.pipeline().names()
                )
                //copy the content to avoid release this handler
                ctx.fireChannelRead(ReferenceCountUtil.releaseLater(msg.content().copy()))
            }

            is ByteBuf -> {
                logger.warn(
                    "[${
                        ctx.channel().id().asShortText()
                    }], WebSocket Client receive message:{}, pipeline handlers:{}",
                    msg.javaClass.name,
                    ctx.pipeline().names()
                )
                ctx.fireChannelRead(ReferenceCountUtil.releaseLater(msg.copy()))

            }
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        logger.trace(
            "[${
                ctx.channel().id().asShortText()
            }], WebSocket Client send message:{}, pipeline handlers:{}", msg.javaClass.name, ctx.pipeline().names()
        )
        Websocket.websocketWrite(ctx, msg, promise)
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
