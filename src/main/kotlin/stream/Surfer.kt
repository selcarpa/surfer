package stream


import inbounds.Websocket
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
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
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.RELAY_HANDLER_NAME
import model.config.Outbound
import model.config.WsOutboundSetting
import mu.KotlinLogging
import utils.ChannelUtils
import java.net.InetSocketAddress
import java.net.URI


class Surfer {

    companion object {
        private val logger = KotlinLogging.logger {}

        fun outbound(
            outbound: Outbound,
            connectListener: FutureListener<Channel>,
            socketAddress: InetSocketAddress? = null,
            eventLoopGroup: EventLoopGroup = NioEventLoopGroup()
        ) {
            if (outbound.outboundStreamBy == null) {
                return galaxy(connectListener, socketAddress!!, eventLoopGroup)
            }
            return when (outbound.outboundStreamBy.type) {
                "ws", "wss" -> wsStream(
                    connectListener,
                    outbound.outboundStreamBy.wsOutboundSetting,
                    outbound.outboundStreamBy.type,
                    eventLoopGroup
                )

                else -> {
                    logger.error { "stream type ${outbound.outboundStreamBy.type} not supported" }
                }
            }
        }

        private fun galaxy(
            connectListener: FutureListener<Channel>,
            socketAddress: InetSocketAddress,
            eventLoopGroup: EventLoopGroup
        ) {
            val b = Bootstrap()
            val promise = eventLoopGroup.next().newPromise<Channel>()
            promise.addListener(connectListener)
            b.group(eventLoopGroup).channel(NioSocketChannel::class.java).option(ChannelOption.TCP_NODELAY, true)
                .handler(LoggingHandler("galaxy logger", LogLevel.DEBUG, ByteBufFormat.HEX_DUMP))
                .handler(object : ChannelInboundHandlerAdapter() {
                    override fun channelActive(ctx: ChannelHandlerContext) {
                        super.channelActive(ctx)
                        promise.setSuccess(ctx.channel())
                    }

                    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                        logger.debug(
                            "[{}], galaxy read {}, pipelines: {}",
                            ctx.channel().id().asShortText(),
                            msg,
                            ctx.channel().pipeline().names()
                        )
                        super.channelRead(ctx, msg)
                    }
                })
            b.connect(socketAddress)
        }

        private fun wsStream(
            connectListener: FutureListener<Channel>,
            wsOutboundSetting: WsOutboundSetting,
            type: String,
            eventLoopGroup: EventLoopGroup
        ) {
            val b = Bootstrap()
            val promise = eventLoopGroup.next().newPromise<Channel>()
            promise.addListener(connectListener)

            val uri = URI("${type}://${wsOutboundSetting.host}:${wsOutboundSetting.port}${wsOutboundSetting.path}")
            val scheme = if (uri.scheme == null) "ws" else uri.scheme
            val port: Int = if (uri.port == -1) {
                if ("ws".equals(scheme, ignoreCase = true)) {
                    80
                } else if ("wss".equals(scheme, ignoreCase = true)) {
                    443
                } else {
                    -1
                }
            } else {
                uri.port
            }


            val ssl = "wss".equals(type, ignoreCase = true)
            val sslCtx: SslContext? = if (ssl) {
                SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            } else {
                null
            }
            // Connect with V13 (RFC 6455 aka HyBi-17). You can change it to V08 or V00.
            // If you change it to V00, ping is not supported and remember to change
            // HttpResponseDecoder to WebSocketHttpResponseDecoder in the pipeline.
            val webSocketClientHandler = WebSocketClientHandler(
                WebSocketClientHandshakerFactory.newHandshaker(
                    uri,
                    WebSocketVersion.V13,
                    null,
                    true,
                    DefaultHttpHeaders()
                ), promise
            )

            b.group(eventLoopGroup).channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        if (sslCtx != null) {
                            ch.pipeline()
                                .addLast(sslCtx.newHandler(ch.alloc(), wsOutboundSetting.host, wsOutboundSetting.port))
                        }

                        ch.pipeline().addLast(
                            HttpClientCodec(),
                            HttpObjectAggregator(8192),
                            WebSocketClientCompressionHandler.INSTANCE,
                            webSocketClientHandler
                        )
                    }

                })
            b.connect(uri.host, port)

        }
    }

}

class WebSocketClientHandler(
    private val handshaker: WebSocketClientHandshaker,
    private val connectPromise: Promise<Channel>
) : ChannelDuplexHandler() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        handshaker.handshake(ctx.channel())
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        //        logger.debug { "[${ctx.channel().id().asShortText()}] WebSocket Client received message: $msg" }
        val ch = ctx.channel()
        if (!handshaker.isHandshakeComplete) {
            try {
                handshaker.finishHandshake(ch, msg as FullHttpResponse?)
                logger.debug("[${ctx.channel().id().asShortText()}] WebSocket Client connected!")
                connectPromise.setSuccess(ctx.channel())
            } catch (e: WebSocketHandshakeException) {
                logger.debug("WebSocket Client failed to connect")
                connectPromise.setFailure(e)
            }
            return
        }
        when (msg) {
            is FullHttpResponse -> {
                throw IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + msg.status() + ", content=" + msg.content()
                        .toString(CharsetUtil.UTF_8) + ')'
                )
            }

            is TextWebSocketFrame -> {
                logger.debug(
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
                logger.debug(
                    "[${
                        ctx.channel().id().asShortText()
                    }], WebSocket Client receive message:{}, pipeline handlers:{}",
                    msg.javaClass.name,
                    ctx.pipeline().names()
                )
                //copy the content to avoid release this handler
                ctx.fireChannelRead(msg.content().copy())
            }

            is ByteBuf -> {
                logger.debug(
                    "[${
                        ctx.channel().id().asShortText()
                    }], WebSocket Client receive message:{}, pipeline handlers:{}",
                    msg.javaClass.name,
                    ctx.pipeline().names()
                )
                ctx.fireChannelRead(msg.copy())

            }
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        logger.debug(
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
            logger.debug(
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
