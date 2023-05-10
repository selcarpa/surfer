package netty


import TrojanInboundHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateHandler
import model.config.ConfigurationSettings.Companion.Configuration
import model.config.Inbound
import mu.KotlinLogging
import netty.inbounds.HttpProxyServerHandler
import netty.inbounds.SocksServerHandler
import netty.inbounds.WebsocketInboundHandler
import java.util.function.Function
import java.util.stream.Collectors

class ProxyChannelInitializer : ChannelInitializer<NioSocketChannel>() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun initChannel(ch: NioSocketChannel) {

        val localAddress = ch.localAddress()

        val portInboundMap =
            Configuration.inbounds.stream().collect(Collectors.toMap(Inbound::port, Function.identity()))
        val inbound = portInboundMap[localAddress.port]
        //todo refactor to strategy pattern
        if (inbound != null) {
            when (inbound.protocol) {
                "http" -> {
                    initHttpInbound(ch, inbound)
                    return
                }

                "socks5" -> {
                    initSocksInbound(ch, inbound)
                    return
                }

                "trojan" -> {
                    initTrojanInbound(ch, inbound)
                    return
                }
            }
        } else {
            logger.error("not support inbound")
            ch.close()
        }
    }

    private fun initSocksInbound(ch: NioSocketChannel, inbound: Inbound) {
        ch.pipeline().addLast(SocksPortUnificationServerHandler())
        ch.pipeline().addLast(SocksServerHandler(inbound))
    }

    private fun initHttpInbound(ch: NioSocketChannel, inbound: Inbound) {
        ch.pipeline().addLast(
            ChunkedWriteHandler(),
            HttpServerCodec(),
            HttpContentCompressor(),
            HttpObjectAggregator(Int.MAX_VALUE),
            HttpProxyServerHandler(inbound)
        )
    }

    private fun initTrojanInbound(ch: NioSocketChannel, inbound: Inbound) {
        when (inbound.inboundStreamBy!!.type) {
            "ws" -> {
                ch.pipeline().addLast(
                    ChunkedWriteHandler(),
                    HttpServerCodec(),
                    HttpObjectAggregator(Int.MAX_VALUE),
                    IdleStateHandler(60, 60, 60),
                    WebSocketServerProtocolHandler(inbound.inboundStreamBy.wsInboundSettings[0].path),
                    //todo solve sub-protocol
                    WebsocketInboundHandler { ctx, _ ->
                        ctx.pipeline().addLast(TrojanInboundHandler(inbound))
                    })
            }
        }

    }
}
