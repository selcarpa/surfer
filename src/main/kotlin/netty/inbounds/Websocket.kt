package netty.inbounds


import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import mu.KotlinLogging

class WebsocketInboundHandler(
    private val handshakeCompleteCallBack: (ctx: ChannelHandlerContext, evt: WebSocketServerProtocolHandler.HandshakeComplete) -> Unit
) : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
            logger.debug("WebsocketInbound HandshakeComplete")
            handshakeCompleteCallBack(ctx, evt)
        }
        super.userEventTriggered(ctx, evt)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        logger.debug("WebsocketInbound receive message:${msg.javaClass.name}")
        when (msg) {
            is FullHttpRequest -> {
                //ignored
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
                logger.debug(
                    "WebsocketInbound receive message:${msg.javaClass.name} ${msg.text()}"
                )
            }

            is BinaryWebSocketFrame -> {
                logger.debug(
                    "WebsocketInbound receive message:{}, pipeline handlers:{}",
                    msg.javaClass.name,
                    ctx.pipeline().names()
                )
                ctx.fireChannelRead(msg.content())
            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
            }
        }
    }

}

