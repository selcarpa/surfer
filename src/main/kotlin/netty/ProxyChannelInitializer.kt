package netty


import inbounds.HttpProxyServerHandler
import inbounds.SocksServerHandler
import io.netty.contrib.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty5.channel.Channel
import io.netty5.channel.ChannelInitializer
import io.netty5.channel.socket.nio.NioSocketChannel
import io.netty5.handler.codec.http.HttpContentCompressor
import io.netty5.handler.codec.http.HttpObjectAggregator
import io.netty5.handler.codec.http.HttpServerCodec
import io.netty5.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty5.handler.ssl.SslContext
import io.netty5.handler.ssl.SslContextBuilder
import io.netty5.handler.stream.ChunkedWriteHandler
import io.netty5.handler.timeout.IdleStateHandler
import io.netty5.util.concurrent.Promise
import model.config.Config.Configuration
import model.config.Inbound
import model.config.TlsInboundSetting
import model.config.WsInboundSetting
import model.protocol.Protocol
import mu.KotlinLogging
import protocol.TrojanInboundHandler
import stream.SslActiveHandler
import stream.WebsocketDuplexHandler
import java.io.File
import java.net.InetSocketAddress
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
        val inbound = portInboundMap[(localAddress as InetSocketAddress).port]
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IdleStateHandler(300, 300, 300))
        ch.pipeline().addFirst(IdleCloseHandler())
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
                val handleShakePromise = ch.executor().next().newPromise<Channel>()
                handleShakePromise.asFuture().addListener { future ->
                    if (future.isSuccess) {
                        future.now.pipeline().addLast(TrojanInboundHandler(inbound))
                    }
                }

                initWebsocketInbound(ch, inbound.inboundStreamBy.wsInboundSetting!!, handleShakePromise)
            }
            Protocol.TLS->{
                val handleShakePromise = ch.executor().next().newPromise<Channel>()
                handleShakePromise.asFuture().addListener { future ->
                    if (future.isSuccess) {
                        future.now.pipeline().addLast(TrojanInboundHandler(inbound))
                    }
                }

                initTlsInbound(ch, inbound.inboundStreamBy.tlsInboundSetting!!, handleShakePromise)
            }

            else -> {
                logger.error("not support inbound stream by: ${inbound.inboundStreamBy.type}")
                ch.close()
            }
        }

    }

    private fun initTlsInbound(
        ch: NioSocketChannel,
        tlsInboundSetting: TlsInboundSetting,
        handleShakePromise: Promise<Channel>
    ) {
        val sslCtx: SslContext = if (tlsInboundSetting.password != null) {
            SslContextBuilder.forServer(
                File(tlsInboundSetting.keyCertChainFile),
                File(tlsInboundSetting.keyFile),
                tlsInboundSetting.password
            ).build()
        } else {
            SslContextBuilder.forServer(File(tlsInboundSetting.keyCertChainFile), File(tlsInboundSetting.keyFile)).build()
        }
        ch.pipeline().addLast(
            sslCtx.newHandler(ch.bufferAllocator()),
            SslActiveHandler(handleShakePromise)
        )
    }

    private fun initWebsocketInbound(
        ch: NioSocketChannel,
        wsInboundSetting: WsInboundSetting,
        handleShakePromise: Promise<Channel>
    ) {
        ch.pipeline().addLast(
            ChunkedWriteHandler(),
            HttpServerCodec(),
            HttpObjectAggregator(Int.MAX_VALUE),
            WebSocketServerProtocolHandler(wsInboundSetting.path),
            WebsocketDuplexHandler(handleShakePromise)
        )
    }
}


