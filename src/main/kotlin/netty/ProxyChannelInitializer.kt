package netty


import inbounds.HttpProxyServerHandler
import inbounds.SocksServerHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.config.ConfigurationSettings.Companion.Configuration
import model.config.Inbound
import model.protocol.Protocol
import mu.KotlinLogging
import protocol.TrojanInboundHandler
import stream.WebsocketDuplexHandler
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
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IdleStateHandler(300, 300, 300))
        ch.pipeline().addFirst(IdleCloseHandler())
        //todo refactor to strategy pattern
        if (inbound != null) {
            when (Protocol.valueOfOrNull(inbound.protocol)) {
                Protocol.HTTP -> {
                    initHttpInbound(ch, inbound)
                    return
                }

                Protocol.SOCKS5 -> {
                    initSocksInbound(ch, inbound)
                    return
                }

                Protocol.TROJAN -> {
                    initTrojanInbound(ch, inbound)
                    return
                }

                else -> {
                    //ignored
                }
            }
        }
        logger.error(
            "not support inbound: ${inbound?.protocol}"
        )
        ch.close()

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
        when (Protocol.valueOfOrNull(inbound.inboundStreamBy!!.type)) {
            Protocol.WS -> {
                val handleShakePromise = ch.eventLoop().next().newPromise<Channel>()
                handleShakePromise.addListener(FutureListener { future ->
                    if (future.isSuccess) {
                        future.get().pipeline().addLast(TrojanInboundHandler(inbound))
                    }
                })

                initWebsocketInbound(ch, inbound.inboundStreamBy.wsInboundSetting.path, handleShakePromise)
            }

            else -> {
                logger.error("not support inbound stream by: ${inbound.inboundStreamBy.type}")
                ch.close()
            }
        }

    }

    private fun initWebsocketInbound(
        ch: NioSocketChannel,
        path: String,
        handleShakePromise: Promise<Channel>
    ) {
        ch.pipeline().addLast(
            ChunkedWriteHandler(),
            HttpServerCodec(),
            HttpObjectAggregator(Int.MAX_VALUE),
            WebSocketServerProtocolHandler(path),
            WebsocketDuplexHandler(handleShakePromise)
        )
    }
}


