package netty.inbounds

import io.klogging.NoCoLogging
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import kotlin.math.log

class WebsocketInbound() : ChannelInboundHandlerAdapter(), NoCoLogging {


    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {

        logger.debug("WebsocketInbound receive message:${msg.javaClass.name}")
        when (msg) {
            is FullHttpRequest -> {
                ctx.fireChannelRead(msg)
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
                ctx.fireChannelRead(msg)
            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
            }
        }
    }
}
