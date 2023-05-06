package netty


import TrojanInboundHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateHandler
import model.config.ConfigurationHolder
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

        val configuration = ConfigurationHolder.configuration
        val portInboundMap =
            configuration.inbounds.stream().collect(Collectors.toMap(Inbound::port, Function.identity()))
        val inbound = portInboundMap[localAddress.port]
        //todo refactor to strategy pattern
        if (inbound != null) {
            when (inbound.protocol) {
                "http" -> {
                    initHttpInbound(ch, inbound)
                    return
                }

                "socks5", "socks4", "socks4a" -> {
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
        // http proxy send a http response to client
        ch.pipeline().addLast(HttpResponseEncoder())
        // http proxy send a http request to server
        ch.pipeline().addLast(HttpRequestDecoder())
        ch.pipeline().addLast(ChunkedWriteHandler())
        ch.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
        ch.pipeline().addLast(HttpContentCompressor())
        ch.pipeline().addLast(HttpServerCodec())
        ch.pipeline().addLast(HttpProxyServerHandler(inbound))
    }

    private fun initTrojanInbound(ch: NioSocketChannel, inbound: Inbound) {
        when (inbound.inboundStreamBy!!.type) {
            "ws" -> {
                ch.pipeline().addLast(HttpServerCodec())
                ch.pipeline().addLast(ChunkedWriteHandler())
                ch.pipeline().addLast(HttpObjectAggregator(65536))
                ch.pipeline().addLast(IdleStateHandler(60, 60, 60))
                ch.pipeline().addLast(WebSocketServerProtocolHandler(inbound.inboundStreamBy.wsInboundSettings[0].path))
                ch.pipeline().addLast(WebsocketInboundHandler { ctx, _ ->
                    ctx.pipeline().addLast(TrojanInboundHandler(inbound))
                })
            }
        }

    }
}
