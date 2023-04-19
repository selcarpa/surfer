package netty

import io.klogging.NoCoLogging
import io.netty.channel.ChannelHandlerAdapter
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder
import io.netty.handler.stream.ChunkedWriteHandler
import model.config.ConfigurationHolder
import model.config.Inbound
import netty.inbounds.HttpProxyServerHandler
import netty.inbounds.Socks5CommandRequestInboundHandler
import netty.inbounds.Socks5InitialRequestInboundHandler
import java.util.function.Function
import java.util.stream.Collectors

class ProxyChannelInitializer : NoCoLogging, ChannelInitializer<NioSocketChannel>() {


    override fun initChannel(ch: NioSocketChannel) {

        val localAddress = ch.localAddress()

        val configuration = ConfigurationHolder.configuration
        val portInboundMap =
            configuration.inbounds.stream().collect(Collectors.toMap(Inbound::port, Function.identity()))
        val inbound = portInboundMap.get(localAddress.port)

        ch.pipeline().addLast(object : ChannelHandlerAdapter() {
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                logger.error(
                    "id :${ctx.channel().id().asShortText()}, ${
                        ctx.channel().id().asShortText()
                    } exception caught: ${cause.message}"
                )
            }
        })

        //todo refactor to strategy pattern
        if (inbound != null) {
            if (inbound.protocol == "http") {
                initHttpInbound(ch)
                return
            } else if (inbound.protocol == "socks5") {
                initSocks5Inbound(ch, inbound)
                return
            }
        }


    }

    private fun initSocks5Inbound(ch: NioSocketChannel, inbound: Inbound) {
        ch.pipeline().addLast(Socks5ServerEncoder.DEFAULT)
//        ch.pipeline().addLast(object : Socks5InitialRequestDecoder() {
//            override fun decode(ctx: ChannelHandlerContext?, `in`: ByteBuf?, out: MutableList<Any>?) {
//                //print
//                val currentAllBytes = ByteArray(`in`!!.readableBytes())
//                `in`.readBytes(currentAllBytes)
//                logger.debug("plain package: ${ByteBufUtil.hexDump(currentAllBytes)}")
//
//                super.decode(ctx, `in`, out)
//            }
//        })
        ch.pipeline().addLast(Socks5InitialRequestDecoder())
        ch.pipeline().addLast(Socks5InitialRequestInboundHandler())
        ch.pipeline().addLast(Socks5CommandRequestDecoder())
        ch.pipeline().addLast(Socks5CommandRequestInboundHandler(clientWorkGroup,inbound))
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
