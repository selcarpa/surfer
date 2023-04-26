package netty.inbounds


import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import mu.KotlinLogging
import kotlin.math.log

class WebsocketInbound() : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }


    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {

        when (msg) {
            is FullHttpRequest -> {
                val wsFactory = WebSocketServerHandshakerFactory("0.0.0.0:14271", null, false)
                val handshaker = wsFactory.newHandshaker(msg)
                handshaker.handshake(ctx.channel(), msg).addListener {
                    logger.debug("${ctx.channel().id().asShortText()} WebsocketInbound handshake success")
                }
            }

            is CloseWebSocketFrame -> {
                ctx.close()
            }

            is PingWebSocketFrame -> {
                ctx.writeAndFlush(PongWebSocketFrame())
            }

            is PongWebSocketFrame -> {
                //ignored
            }

            is TextWebSocketFrame -> {
                ctx.fireChannelRead(msg)
            }

            is BinaryWebSocketFrame -> {
                val currentAllBytes = ByteArray(msg.content().readableBytes())
                msg.content().readBytes(currentAllBytes)
                logger.debug(
                    "WebsocketInbound receive message:${msg.javaClass.name} ${ByteBufUtil.hexDump(currentAllBytes)}"
                )
                ctx.fireChannelRead(msg)
            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
            }
        }
    }
}
