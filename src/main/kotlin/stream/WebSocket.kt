package stream


import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise

private val logger = KotlinLogging.logger {}

class WebSocketDuplexHandler(private val handleShakePromise: Promise<Channel>? = null) : ChannelDuplexHandler() {


    private var continuationBuffer: ByteBuf? = null

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        //when surfer as a websocket server, we need to handle handshake complete event to determine whether the handshake is successful, and start the relay operation
        if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
            logger.trace { "[${ctx.channel().id()}] WebsocketInbound handshake complete" }
            handleShakePromise?.setSuccess(ctx.channel())
        }
        //when surfer as a websocket client, we also need to handle handshake complete event to determine whether the handshake is successful, and start the relay operation
        if (evt is WebSocketClientProtocolHandler.ClientHandshakeStateEvent) {
            if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                logger.trace { "[${ctx.channel().id()}] WebsocketInbound handshake complete" }
                handleShakePromise?.setSuccess(ctx.channel())
            } else if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
                logger.error { "[${ctx.channel().id()}] WebsocketInbound handshake timeout" }
                handleShakePromise?.setFailure(Throwable("websocket handshake failed"))
            }
        }
        logger.trace { "[${ctx.channel().id()}] userEventTriggered: $evt" }
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
        logger.trace {
            "[${
                ctx.channel().id().asShortText()
            }] WebsocketInbound receive message:${msg.javaClass.name}"
        }
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
                logger.trace { "[${ctx.channel().id().asShortText()}] receive text message: ${msg.text()}" }
            }

            is BinaryWebSocketFrame -> {
                logger.trace {
                    "${"WebsocketInbound receive message:{}, pipeline handlers:{}"} ${msg.javaClass.name} ${
                        ctx.pipeline().names()
                    }"
                }
                ctx.fireChannelRead(msg.content())
            }

            is ContinuationWebSocketFrame -> {
                logger.debug { "WebsocketInbound receive ContinuationWebSocketFrame:${msg.javaClass.name}" }
                if (msg.isFinalFragment) {
                    if (continuationBuffer != null) {
                        continuationBuffer!!.writeBytes(msg.content())
                        ReferenceCountUtil.release(msg)
                        ctx.fireChannelRead(continuationBuffer)
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
                logger.error { "WebsocketInbound receive unknown message:${msg.javaClass.name}" }
                ReferenceCountUtil.release(msg)
            }
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        when (msg) {
            is ByteBuf -> {
                //todo: fix leak
                val binaryWebSocketFrame = BinaryWebSocketFrame(msg)
                ctx.write(binaryWebSocketFrame).addListener {
                    FutureListener<Unit> {
                        ReferenceCountUtil.release(binaryWebSocketFrame)
                        if (!it.isSuccess) {
                            logger.error(
                                it.cause()
                            ) {
                                "write message:${msg.javaClass.name} to ${
                                    ctx.channel().id().asShortText()
                                } failed ${ctx.channel().pipeline().names()}"
                            }
                        }
                    }
                }
            }

            else -> {
                logger.info {" ctx.pipeline().names(): ${msg.javaClass.name}" }
                ctx.write(msg, promise)
            }
        }
    }
}
