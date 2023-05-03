package netty.inbounds


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.function.Consumer

class HttpProxyServerHandler :  ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val http = true
        //http proxy and http connect method
        if (msg is io.netty.handler.codec.http.HttpRequest) {
            val request = msg as DefaultHttpRequest
            val host = request.headers()["host"]
            logger.debug("host:{},method:{}", host, request.method())

            //https://developer.mozilla.org/zh-CN/docs/Web/HTTP/Proxy_servers_and_tunneling#http_tunneling
            //Tunneling transmits private network data and protocol information through public network by encapsulating the data. HTTP tunneling is using a protocol of higher level (HTTP) to transport a lower level protocol (TCP).
            //The HTTP protocol specifies a request method called CONNECT. It starts two-way communications with the requested resource and can be used to open a tunnel. This is how a client behind an HTTP proxy can access websites using SSL (i.e. HTTPS, port 443). Note, however, that not all proxy servers support the CONNECT method or limit it to port 443 only.
            if (HttpMethod.CONNECT == request.method()) {
                logger.debug("connecting,url:{}", request.uri())
                val response: io.netty.handler.codec.http.HttpResponse =
                    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus(200, "Connection established"))
                ctx.writeAndFlush(response)
                ReferenceCountUtil.release(msg)
                logger.debug("connected,url:{}", request.uri())
                return
            }


            //http request resend
            val httpClient = HttpClient.newHttpClient()
            val httpRequest = HttpRequest.newBuilder().uri(URI.create(request.uri())).GET().build()
            val sent = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val response: FullHttpResponse = DefaultFullHttpResponse(
                request.protocolVersion(),
                HttpResponseStatus.valueOf(sent.statusCode()),
                Unpooled.wrappedBuffer(sent.body().toByteArray())
            )
            sent.headers().map().keys.forEach(Consumer { it: String? ->
                response.headers()[it] = sent.headers().map()[it]
            })
            ctx.write(response)
            ctx.flush()
        } else {
            //other message
        }
    }

    private fun doHandle(ctx: ChannelHandlerContext, msg: Any, http: Boolean) {


        if (http && msg is io.netty.handler.codec.http.HttpRequest) {
            val bootstrap = Bootstrap()

            bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<NioSocketChannel>() {
                    override fun initChannel(ch: NioSocketChannel) {
                        ch.pipeline().addLast(
                            "httpCodec", HttpClientCodec()
                        )
                        ch.pipeline().addLast("proxyClientHandle", object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx1: ChannelHandlerContext, msg: Any) {
                                //if client channel is closed, then don't proxy
                                val clientChannel = ctx.channel()
                                if (!clientChannel.isOpen) {
                                    ReferenceCountUtil.release(msg)
                                    return
                                }
                                clientChannel.writeAndFlush(msg)
                            }
                        })
                    }
                })
        }
    }
}
