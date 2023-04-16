package netty

import io.klogging.NoCoLogging
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import io.netty.handler.stream.ChunkedWriteHandler
import model.config.ConfigurationHolder
import model.config.Inbound
import java.util.function.Function
import java.util.stream.Collectors

class ProxyChannelInitializer : NoCoLogging, ChannelInitializer<NioSocketChannel>() {


    override fun initChannel(ch: NioSocketChannel) {

        val localAddress = ch.localAddress()

        val configuration = ConfigurationHolder.configuration
        val portInboundMap =
            configuration.inbounds.stream().collect(Collectors.toMap(Inbound::port, Function.identity()))
        val get = portInboundMap.get(localAddress.port)

        ch.pipeline().addLast(object : ChannelHandlerAdapter() {
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                logger.error("${ctx.channel().id().asShortText()} exception caught: ${cause.message}")
            }
        })

        //todo refactor to strategy pattern
        if (get != null) {
            if (get.protocol == "http") {
                initHttpInbound(ch)
                return
            } else if (get.protocol == "socks5") {
                initSocks5Inbound(ch)
                return
            }
        }


    }

    private fun initSocks5Inbound(ch: NioSocketChannel) {
        ch.pipeline().addLast(Socks5ServerEncoder.DEFAULT)
        ch.pipeline().addLast(Socks5InitialRequestDecoder())
        ch.pipeline().addLast(Socks5InitialRequestInboundHandler())
        //todo authentication
        ch.pipeline().addLast(Socks5CommandRequestDecoder())
        ch.pipeline().addLast(Socks5CommandRequestInboundHandler(clientWorkGroup))
    }

    private fun initHttpInbound(ch: NioSocketChannel) {
        // http proxy send a http response to client
        ch.pipeline().addLast(
            HttpResponseEncoder()
        )
        // http proxy send a http request to server
        ch.pipeline().addLast(
            HttpRequestDecoder()
        )
        ch.pipeline().addLast(
            HttpProxyServerHandler()
        )
        ch.pipeline().addLast(ChunkedWriteHandler())
        ch.pipeline().addLast("aggregator", HttpObjectAggregator(10 * 1024 * 1024))
        ch.pipeline().addLast("compressor", HttpContentCompressor())
        ch.pipeline().addLast(HttpServerCodec())
    }

    companion object {
        var clientWorkGroup: EventLoopGroup = NioEventLoopGroup()
    }
}
