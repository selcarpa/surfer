package netty


import inbounds.HttpProxyServerHandler
import inbounds.SocksServerHandler
import inbounds.WebsocketDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import model.config.ConfigurationSettings.Companion.Configuration
import model.config.Inbound
import mu.KotlinLogging
import protocol.TrojanInboundHandler
import java.util.function.Function
import java.util.stream.Collectors

class ProxyChannelInitializer : ChannelInitializer<NioSocketChannel>() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun initChannel(ch: NioSocketChannel) {

        val localAddress = ch.localAddress()

        val portInboundMap = Configuration.inbounds.stream().collect(Collectors.toMap(Inbound::port, Function.identity()))
        val inbound = portInboundMap[localAddress.port]
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IdleStateHandler(300,300,300))
        ch.pipeline().addFirst(IdleCloseHandler())
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
            HttpProxyServerHandler(inbound))
    }

    private fun initTrojanInbound(ch: NioSocketChannel, inbound: Inbound) {
        when (inbound.inboundStreamBy!!.type) {
            "ws" -> {
                val handshakeCompleteCallBack: (ctx: ChannelHandlerContext, evt: WebSocketServerProtocolHandler.HandshakeComplete) -> Unit = { ctx, _ ->
                    ctx.pipeline().addLast(TrojanInboundHandler(inbound))
                }
                initWebsocketInbound(ch, inbound.inboundStreamBy.wsInboundSetting.path, handshakeCompleteCallBack)
            }
        }

    }

    private fun initWebsocketInbound(ch: NioSocketChannel, path: String, handshakeCompleteCallBack: (ctx: ChannelHandlerContext, evt: WebSocketServerProtocolHandler.HandshakeComplete) -> Unit) {
        ch.pipeline().addLast(ChunkedWriteHandler(), HttpServerCodec(), HttpObjectAggregator(Int.MAX_VALUE), WebSocketServerProtocolHandler(path), WebsocketDuplexHandler(handshakeCompleteCallBack))
    }
}


/**
 * when channel idle, close it
 */
class IdleCloseHandler: ChannelInboundHandlerAdapter(){
    override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {
        when(evt){
            is IdleStateEvent -> {
                ctx?.close()
            }
        }
        super.userEventTriggered(ctx, evt)
    }
}
