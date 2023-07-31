import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.cookie.ServerCookieEncoder
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.CharsetUtil
import model.config.Config
import netty.NettyServer
import java.net.ServerSocket
import kotlin.test.Test


class ProtocolTest {
    var port: Int = 0

    @Test
    fun mainTest() {
        choosePort()
        startMainServer()
        Thread{
            startDestinationServer()
        }
    }

    private fun choosePort() {
        val serverSocket = ServerSocket(0)

        val port = serverSocket.getLocalPort()

        serverSocket.close()

        this.port = port
    }

    private fun startDestinationServer() {
        // Configure the server.

        // Configure the server.
        val bossGroup: EventLoopGroup = NioEventLoopGroup(1)
        val workerGroup: EventLoopGroup = NioEventLoopGroup()
        try {
            val b = ServerBootstrap()
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .handler(LoggingHandler(LogLevel.INFO))
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val p = ch.pipeline()
                        p.addLast(HttpRequestDecoder())
                        p.addLast(HttpResponseEncoder())
                        p.addLast(HttpSnoopServerHandler())
                    }

                });
            val ch: Channel = b.bind(port).sync().channel()
            Runtime.getRuntime().addShutdownHook(Thread({
                bossGroup.shutdownGracefully()
                workerGroup.shutdownGracefully()
            }, "Server Shutdown Thread"))
            ch.closeFuture().sync()
        } finally {
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    private fun startMainServer() {
        Config.ConfigurationUrl = "classpath:/MainTest.json5"
        NettyServer.start()
    }

}


class HttpSnoopServerHandler : SimpleChannelInboundHandler<Any?>() {
    private var request: HttpRequest? = null

    /** Buffer that stores the response content  */
    private val buf = StringBuilder()
    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: Any?) {
        if (msg is HttpRequest) {
            request = msg
            val request = request
            if (HttpUtil.is100ContinueExpected(request)) {
                send100Continue(ctx)
            }
            buf.setLength(0)
            buf.append("WELCOME TO THE WILD WILD WEB SERVER\r\n")
            buf.append("===================================\r\n")
            buf.append("VERSION: ").append(request!!.protocolVersion()).append("\r\n")
            buf.append("HOSTNAME: ").append(request.headers()[HttpHeaderNames.HOST, "unknown"]).append("\r\n")
            buf.append("REQUEST_URI: ").append(request.uri()).append("\r\n\r\n")
            val headers = request.headers()
            if (!headers.isEmpty) {
                for ((key, value) in headers) {
                    buf.append("HEADER: ").append(key).append(" = ").append(value).append("\r\n")
                }
                buf.append("\r\n")
            }
            val queryStringDecoder = QueryStringDecoder(request.uri())
            val params = queryStringDecoder.parameters()
            if (!params.isEmpty()) {
                for ((key, vals) in params) {
                    for (`val` in vals) {
                        buf.append("PARAM: ").append(key).append(" = ").append(`val`).append("\r\n")
                    }
                }
                buf.append("\r\n")
            }
            appendDecoderResult(buf, request)
        }
        if (msg is HttpContent) {
            val content = msg.content()
            if (content.isReadable) {
                buf.append("CONTENT: ")
                buf.append(content.toString(CharsetUtil.UTF_8))
                buf.append("\r\n")
                appendDecoderResult(buf, request)
            }
            if (msg is LastHttpContent) {
                buf.append("END OF CONTENT\r\n")
                val trailer = msg
                if (!trailer.trailingHeaders().isEmpty) {
                    buf.append("\r\n")
                    for (name in trailer.trailingHeaders().names()) {
                        for (value in trailer.trailingHeaders().getAll(name)) {
                            buf.append("TRAILING HEADER: ")
                            buf.append(name).append(" = ").append(value).append("\r\n")
                        }
                    }
                    buf.append("\r\n")
                }
                if (!writeResponse(trailer, ctx)) {
                    // If keep-alive is off, close the connection once the content is fully written.
                    ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
                }
            }
        }
    }

    private fun writeResponse(currentObj: HttpObject, ctx: ChannelHandlerContext): Boolean {
        // Decide whether to close the connection or not.
        val keepAlive = HttpUtil.isKeepAlive(request)
        // Build the response object.
        val response: FullHttpResponse = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            if (currentObj.decoderResult().isSuccess) HttpResponseStatus.OK else HttpResponseStatus.BAD_REQUEST,
            Unpooled.copiedBuffer(buf.toString(), CharsetUtil.UTF_8)
        )
        response.headers()[HttpHeaderNames.CONTENT_TYPE] = "text/plain; charset=UTF-8"
        if (keepAlive) {
            // Add 'Content-Length' header only for a keep-alive connection.
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
            // Add keep alive header as per:
            // - https://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01.html#Connection
            response.headers()[HttpHeaderNames.CONNECTION] = HttpHeaderValues.KEEP_ALIVE
        }

        // Encode the cookie.
        val cookieString = request!!.headers()[HttpHeaderNames.COOKIE]
        if (cookieString != null) {
            val cookies = ServerCookieDecoder.STRICT.decode(cookieString)
            if (!cookies.isEmpty()) {
                // Reset the cookies if necessary.
                for (cookie in cookies) {
                    response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie))
                }
            }
        } else {
            // Browser sent no cookie.  Add some.
            response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode("key1", "value1"))
            response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode("key2", "value2"))
        }

        // Write the response.
        ctx.write(response)
        return keepAlive
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }

    companion object {
        private fun appendDecoderResult(buf: StringBuilder, o: HttpObject?) {
            val result = o!!.decoderResult()
            if (result.isSuccess) {
                return
            }
            buf.append(".. WITH DECODER FAILURE: ")
            buf.append(result.cause())
            buf.append("\r\n")
        }

        private fun send100Continue(ctx: ChannelHandlerContext) {
            val response: FullHttpResponse =
                DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER)
            ctx.write(response)
        }
    }
}

