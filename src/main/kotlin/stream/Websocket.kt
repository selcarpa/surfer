package stream


import io.netty5.buffer.Buffer
import io.netty5.channel.Channel
import io.netty5.channel.ChannelHandlerAdapter
import io.netty5.channel.ChannelHandlerContext
import io.netty5.handler.codec.http.FullHttpRequest
import io.netty5.handler.codec.http.websocketx.*
import io.netty5.util.ReferenceCountUtil
import io.netty5.util.Resource
import io.netty5.util.concurrent.Future
import io.netty5.util.concurrent.Promise
import mu.KotlinLogging

class WebsocketDuplexHandler(private val handleShakePromise: Promise<Channel>) :
    ChannelHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private var continuationBuffer: Buffer? = null

    override fun channelInboundEvent(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is WebSocketHandshakeCompletionEvent) {
            handleShakePromise.setSuccess(ctx.channel())
        }
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
                //todo: reply ping
//                ctx.writeAndFlush(PongWebSocketFrame())
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
                ctx.fireChannelRead(msg.binaryData())
            }

            is ContinuationWebSocketFrame -> {
                logger.debug { "WebsocketInbound receive ContinuationWebSocketFrame:${msg.javaClass.name}" }
                if (msg.isFinalFragment) {
                    if (continuationBuffer != null) {
                        continuationBuffer!!.writeBytes(msg.binaryData())
                        Resource.dispose(msg)
                        ctx.fireChannelRead(continuationBuffer)
                        continuationBuffer = null
                    } else {
                        ctx.fireChannelRead(msg.binaryData())
                    }
                } else {
                    if (continuationBuffer == null) {
                        continuationBuffer = ctx.bufferAllocator().allocate(0)
                    }
                    continuationBuffer!!.writeBytes(msg.binaryData())
                }


            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
                Resource.dispose(msg)
            }
        }
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any): Future<Void> {
        when (msg) {
            is Buffer -> {
                //todo: fix leak
                //todo: changed write api
                val binaryWebSocketFrame = BinaryWebSocketFrame(msg)
//                ctx.write(binaryWebSocketFrame).addListener {
//                    FutureListener<Unit> {
//                        ReferenceCountUtil.release(binaryWebSocketFrame)
//                        if (!it.isSuccess) {
//                            logger.error(
//                                "write message:${msg.javaClass.name} to ${
//                                    ctx.channel().id().asShortText()
//                                } failed ${ctx.channel().pipeline().names()}", it.cause()
//                            )
//                        }
//                    }
//                }
                return ctx.write(binaryWebSocketFrame)
            }

            else -> {
                return ctx.write(msg)
            }
        }
    }
}

