package netty.stream


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufUtil
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
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.Promise
import model.config.OutboundStreamBy
import model.config.WsOutboundSetting
import mu.KotlinLogging
import java.net.URI


class StreamFactory {
//    val streams:MutableMap<String,MutableList<>>

    companion object {
        private val logger = KotlinLogging.logger {}
        fun getStream(
            outboundStreamBy: OutboundStreamBy
        ): Promise<Channel> {

//            logger.debug("init stream type: ${outboundStreamBy.type}")
            return when (outboundStreamBy.type) {
                "ws", "wss" -> wsStream(
                    outboundStreamBy.wsOutboundSettings[0], outboundStreamBy.type
                )

                else -> throw IllegalArgumentException("stream type ${outboundStreamBy.type} not supported")
            }
        }

        private fun wsStream(
            wsOutboundSetting: WsOutboundSetting,
            type: String
        ): Promise<Channel> {
            val b = Bootstrap()
            val eventLoop = NioEventLoopGroup()
            val promise = eventLoop.next().newPromise<Channel>()

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


            val ssl = "wss".equals(scheme, ignoreCase = true)
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
                    uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
                ),
            )
            b.group(eventLoop).channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        if (sslCtx != null) {
                            ch.pipeline()
                                .addLast(sslCtx.newHandler(ch.alloc(), wsOutboundSetting.host, wsOutboundSetting.port))
                        }
                        //        logger.debug("init ws client: $uri")

                        ch.pipeline().addLast(
                            HttpClientCodec(),
                            HttpObjectAggregator(8192),
                            WebSocketClientCompressionHandler.INSTANCE,
                            webSocketClientHandler
                        )
                    }

                })
            b.connect(uri.host, port).sync()
            webSocketClientHandler.handshakeFuture!!.sync().addListener {
                promise.setSuccess(webSocketClientHandler.handshakeFuture!!.channel())
            }
            return promise
        }
    }

}

class WebSocketClientHandler(
    private val handshaker: WebSocketClientHandshaker,
) : SimpleChannelInboundHandler<Any?>() {
    var handshakeFuture: ChannelPromise? = null

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        handshaker.handshake(ctx.channel()).addListener {
            handshakeFuture!!.setSuccess()
        }
    }

    override fun handlerAdded(ctx: ChannelHandlerContext?) {
        handshakeFuture = ctx!!.newPromise()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.debug("${ctx.channel().id().asShortText()} WebSocket Client inactive!")
    }

    public override fun channelRead0(ctx: ChannelHandlerContext, msg: Any?) {
        val ch = ctx.channel()
        if (!handshaker.isHandshakeComplete) {
            try {
                handshaker.finishHandshake(ch, msg as FullHttpResponse?)
                logger.debug("${ctx.channel().id().asShortText()} WebSocket Client connected!")
            } catch (e: WebSocketHandshakeException) {
                logger.debug("WebSocket Client failed to connect")
            }
            return
        }
        if (msg is FullHttpResponse) {
            throw IllegalStateException(
                "Unexpected FullHttpResponse (getStatus=" + msg.status() + ", content=" + msg.content()
                    .toString(CharsetUtil.UTF_8) + ')'
            )
        }
        when (val frame = msg as WebSocketFrame?) {
            is TextWebSocketFrame -> {
                logger.debug("WebSocket Client received message: " + frame.text())
                ctx.fireChannelRead(frame)
            }

            is PongWebSocketFrame -> {
                logger.debug("WebSocket Client received pong")
            }

            is CloseWebSocketFrame -> {
                logger.debug("WebSocket Client received closing")
                ch.close()
            }

            is BinaryWebSocketFrame -> {
                logger.debug("WebSocket Client received binary:${ByteBufUtil.hexDump(frame.content().array())}")
                ctx.fireChannelRead(frame)
            }
        }
    }
}
