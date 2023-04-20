package netty.stream

import io.klogging.NoCoLogging
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufUtil
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
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
import model.config.StreamBy
import model.config.WsSetting
import java.net.URI
import java.util.*


class StreamFactory : NoCoLogging {
//    val streams:MutableMap<String,MutableList<>>

    companion object : NoCoLogging {
        fun getStream(streamBy: StreamBy): Channel {
            logger.debug("init stream type: ${streamBy.type}")
            return when (streamBy.type) {
                "ws" -> wsStream(streamBy.wsSettings[0])
                else -> throw IllegalArgumentException("stream type ${streamBy.type} not supported")
            }
        }

        private fun wsStream(wsSetting: WsSetting): Channel {

            val uri = URI("wss://${wsSetting.host}:${wsSetting.port}${wsSetting.path}")
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
                SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            } else {
                null
            }
            val group: EventLoopGroup = NioEventLoopGroup()
            try {

                val b = Bootstrap()
                b.group(group)
                    .channel(NioSocketChannel::class.java)
                    .handler(WsClientInitializer(sslCtx, wsSetting))
                val ch: Channel = b.connect(uri.host, port).sync().channel()
                return ch
            } finally {
                group.shutdownGracefully()
            }
        }
    }

}

class WsClientInitializer(private val sslCtx: SslContext?, private val wsSetting: WsSetting) :
    ChannelInitializer<NioSocketChannel>(), NoCoLogging {
    override fun initChannel(ch: NioSocketChannel) {

        val uri: URI = if (sslCtx != null) {
            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), wsSetting.host, wsSetting.port))
            URI("wss://${wsSetting.host}:${wsSetting.port}${wsSetting.path}")
        } else {
            URI("ws://${wsSetting.host}:${wsSetting.port}${wsSetting.path}")
        }

        logger.debug("init ws client: $uri")
        // Connect with V13 (RFC 6455 aka HyBi-17). You can change it to V08 or V00.
        // If you change it to V00, ping is not supported and remember to change
        // HttpResponseDecoder to WebSocketHttpResponseDecoder in the pipeline.
        val webSocketClientHandler = WebSocketClientHandler(
            WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
            )
        )
        ch.pipeline().addLast(
            HttpClientCodec(),
            HttpObjectAggregator(8192),
            WebSocketClientCompressionHandler.INSTANCE,
            webSocketClientHandler
        )

    }

}


class WebSocketClientHandler(private val handshaker: WebSocketClientHandshaker) : SimpleChannelInboundHandler<Any?>(),
    NoCoLogging {
    private var handshakeFuture: ChannelPromise? = null
    fun handshakeFuture(): ChannelFuture? {
        return handshakeFuture
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        handshakeFuture = ctx.newPromise()
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        handshaker.handshake(ctx.channel())
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.debug("WebSocket Client disconnected!")
    }

    @Throws(Exception::class)
    public override fun channelRead0(ctx: ChannelHandlerContext, msg: Any?) {
        val ch = ctx.channel()
        if (!handshaker.isHandshakeComplete) {
            try {
                handshaker.finishHandshake(ch, msg as FullHttpResponse?)
                logger.debug("WebSocket Client connected!")
                handshakeFuture!!.setSuccess()
            } catch (e: WebSocketHandshakeException) {
                logger.debug("WebSocket Client failed to connect")
                handshakeFuture!!.setFailure(e)
            }
            return
        }
        if (msg is FullHttpResponse) {
            throw IllegalStateException(
                "Unexpected FullHttpResponse (getStatus=" + msg.status() +
                        ", content=" + msg.content().toString(CharsetUtil.UTF_8) + ')'
            )
        }
        when (val frame = msg as WebSocketFrame?) {
            is TextWebSocketFrame -> {
                logger.debug("WebSocket Client received message: " + frame.text())
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
            }
        }
    }
}
