package netty.inbounds


import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.DefaultChannelPromise
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.websocketx.*
import model.config.Inbound
import mu.KotlinLogging
import netty.outbounds.GalaxyOutbound
import netty.stream.RelayInboundHandler
import utils.EasyPUtils

class WebsocketInbound(private val inbound: Inbound) : ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }


    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {

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
                binaryFrameMsg(ctx, msg)
            }

            else -> {
                logger.error("WebsocketInbound receive unknown message:${msg.javaClass.name}")
            }
        }
    }

    private fun binaryFrameMsg(originCTX: ChannelHandlerContext, msg: BinaryWebSocketFrame) {
        val currentAllBytes = ByteArray(msg.content().readableBytes())
        msg.content().readBytes(currentAllBytes)
        logger.debug(
            "WebsocketInbound receive message:${msg.javaClass.name} ${ByteBufUtil.hexDump(currentAllBytes)}"
        )
        val resolveOutbound = EasyPUtils.resolveOutbound(inbound)
        resolveOutbound.ifPresent { outbound ->
            when (inbound.protocol) {
                "trojan" -> {
                    GalaxyOutbound.outbound(
                        originCTX,
                        outbound,
                        RelayInboundHandler(originCTX.channel()),
                        {
                            DefaultChannelPromise(originCTX.channel()).setSuccess()
                        },
                        {
                            DefaultChannelPromise(originCTX.channel()).setSuccess()
                        }
                    )
                }
            }
        }

    }

}

