package inbounds


import inbounds.Websocket.websocketWrite
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.concurrent.FutureListener
import mu.KotlinLogging

class WebsocketDuplexHandler(private val handshakeCompleteCallBack: (ctx: ChannelHandlerContext, evt: WebSocketServerProtocolHandler.HandshakeComplete) -> Unit) : ChannelDuplexHandler() {
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
                logger.debug("WebsocketInbound receive message:${msg.javaClass.name} ${msg.text()}")
            }

            is BinaryWebSocketFrame -> {
                logger.debug("WebsocketInbound receive message:{}, pipeline handlers:{}", msg.javaClass.name, ctx.pipeline().names())
                ctx.fireChannelRead(msg.content())
            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
            }
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        websocketWrite(ctx, msg, promise)
    }


}

object Websocket {
    private val logger = KotlinLogging.logger {}
    fun websocketWrite(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        when (msg) {
            is ByteBuf -> {
                val binaryWebSocketFrame = BinaryWebSocketFrame(msg.copy())
                ctx.write(binaryWebSocketFrame).addListener {
                    FutureListener<Unit> {
                        if (!it.isSuccess) {
                            logger.error("write message:${msg.javaClass.name} to ${
                                ctx.channel().id().asShortText()
                            } failed ${ctx.channel().pipeline().names()}", it.cause())
                        }
                    }
                }
            }

            else -> {
                ctx.write(msg, promise)
            }
        }
    }
}
