package netty.inbounds


import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.*
import io.netty.handler.stream.ChunkedWriteHandler
import model.config.Inbound
import mu.KotlinLogging
import netty.outbounds.GalaxyOutbound
import utils.EasyPUtils
import java.net.URI

class HttpProxyServerHandler(private val inbound: Inbound) : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelRead(originCTX: ChannelHandlerContext, msg: Any) {
        //http proxy and http connect method
        if (msg is HttpRequest) {

            if (msg.method() == HttpMethod.CONNECT) {
                tunnelProxy(originCTX, msg)
            } else {
                httpProxy(originCTX, msg)
            }
        } else {
            //other message
        }
    }

    private fun httpProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        val uri = URI(request.uri())
        val resolveOutbound = EasyPUtils.resolveOutbound(inbound)

        val port = when (uri.port) {
            -1 -> 80;
            else -> uri.port
        }
        resolveOutbound.ifPresent { outbound ->
            when (outbound.protocol) {
                "galaxy" -> {
                    GalaxyOutbound.outbound(
                        originCTX,
                        outbound,
                        uri.host,
                        port,
                        {
                            it.pipeline().addLast(
                                HttpResponseEncoder(),
                                HttpRequestDecoder(),
                                ChunkedWriteHandler(),
                                HttpContentCompressor(),
                                HttpServerCodec()
                            ).newPromise().setSuccess()
                        },
                        {
                            originCTX.channel().newPromise().setSuccess()
                        }
                    )
                }

                "trojan" -> {


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

    private fun tunnelProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        TODO("Not yet implemented")
    }
}
