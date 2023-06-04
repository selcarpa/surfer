package inbounds


import inbounds.Websocket.websocketWrite
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import mu.KotlinLogging

class WebsocketDuplexHandler(private val handshakeCompleteCallBack: (ctx: ChannelHandlerContext, evt: WebSocketServerProtocolHandler.HandshakeComplete) -> Unit) :
    ChannelDuplexHandler() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private var continuationBuffer: ByteBuf? = null

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
            handshakeCompleteCallBack(ctx, evt)
        }
        super.userEventTriggered(ctx, evt)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (continuationBuffer != null) {
            ReferenceCountUtil.release(continuationBuffer)
            continuationBuffer = null
        }
        super.channelInactive(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        logger.trace("WebsocketInbound receive message:${msg.javaClass.name}")
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
                logger.trace("WebsocketInbound receive message:${msg.javaClass.name} ${msg.text()}")
            }

            is BinaryWebSocketFrame -> {
                logger.trace(
                    "WebsocketInbound receive message:{}, pipeline handlers:{}",
                    msg.javaClass.name,
                    ctx.pipeline().names()
                )
                ctx.fireChannelRead(msg.content())
            }

            is ContinuationWebSocketFrame -> {
                if (msg.isFinalFragment) {
                    if (continuationBuffer != null) {
                        continuationBuffer!!.writeBytes(msg.content())
                        ctx.fireChannelRead(ReferenceCountUtil.releaseLater(continuationBuffer!!.copy()))
                        ReferenceCountUtil.release(continuationBuffer)
                        continuationBuffer = null
                    } else {
                        ctx.fireChannelRead(msg.content())
                    }
                } else {
                    if (continuationBuffer == null) {
                        continuationBuffer = ctx.alloc().buffer()
                    }
                    continuationBuffer!!.writeBytes(msg.content())
                }


            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
                ReferenceCountUtil.release(msg)
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
                //todo: fix leak
                val binaryWebSocketFrame = BinaryWebSocketFrame(msg)
                ctx.write(binaryWebSocketFrame).addListener {
                    FutureListener<Unit> {
                        ReferenceCountUtil.release(binaryWebSocketFrame)
                        if (!it.isSuccess) {
                            logger.error(
                                "write message:${msg.javaClass.name} to ${
                                    ctx.channel().id().asShortText()
                                } failed ${ctx.channel().pipeline().names()}", it.cause()
                            )
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
