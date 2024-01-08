import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import io.netty.handler.codec.http.cookie.ServerCookieEncoder
import io.netty.util.CharsetUtil

/**
 * copy from https://github.com/netty/netty/tree/4.1/example/src/main/java/io/netty/example/http/snoop
 */
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
                headers.forEach { (key, value) ->
                    buf.append("HEADER: ").append(key).append(" = ").append(value).append("\r\n")
                }
                buf.append("\r\n")
            }
            val queryStringDecoder = QueryStringDecoder(request.uri())
            val params = queryStringDecoder.parameters()
            if (params.isNotEmpty()) {
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
            if (cookies.isNotEmpty()) {
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

    @Suppress("OVERRIDE_DEPRECATION")
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
