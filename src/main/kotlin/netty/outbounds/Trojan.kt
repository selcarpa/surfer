package netty.outbounds


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.util.ReferenceCountUtil
import model.config.TrojanSetting
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import netty.stream.RelayInboundHandler
import utils.BigEndianUtils
import utils.Sha224Utils

class TrojanOutbound : ChannelOutboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        when (msg) {
            is TrojanPackage -> {
                val h1 = ByteBufUtil.decodeHexDump(msg.hexSha224Password)
                val h2 = ByteBufUtil.decodeHexDump("0d0a")
                val h3 = msg.request.cmd.byteValue()
                val h4 = msg.request.atyp.byteValue()
                val h5 = msg.request.host.toByteArray()
                val h6 = BigEndianUtils.int2ByteArrayTrimZero(msg.request.port, 2)
                val out = Unpooled.buffer()
                out.writeBytes(h1)
                out.writeBytes(h2)
                out.writeBytes(h3)
                out.writeBytes(h4)
                out.writeBytes(h5)
                out.writeBytes(h6)
                out.writeBytes(h2)
                out.writeBytes(ByteBufUtil.decodeHexDump(msg.payload))
                val binaryWebSocketFrame = BinaryWebSocketFrame(out)
                logger.info(
                    "${ctx.channel().id().asShortText()} pipeline handlers: ${
                        ctx.channel().pipeline().names()
                    } write message:${binaryWebSocketFrame.javaClass.name}"
                )
                ctx.write(binaryWebSocketFrame).addListener {
                    if (!it.isSuccess) {
                        logger.error(
                            "write message:${msg.javaClass.name} to ${
                                ctx.channel().id().asShortText()
                            } failed ${ctx.channel().pipeline().names()}",
                            it.cause()
                        )
                    }
                }
            }

            else -> {
                super.write(ctx, msg, promise)
            }
        }
    }
}

private fun ByteBuf.writeBytes(byteValue: Byte) {
    val bytes = ByteArray(1)
    bytes[0] = byteValue
    this.writeBytes(bytes)
}

class TrojanRelayInboundHandler(
    relayChannel: Channel, private val trojanSetting: TrojanSetting, private val trojanRequest: TrojanRequest
) : RelayInboundHandler(relayChannel) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is ByteBuf -> {
                val currentAllBytes = ByteArray(msg.readableBytes())
                msg.readBytes(currentAllBytes)
                ReferenceCountUtil.release(msg)
                val trojanPackage = TrojanPackage(
                    Sha224Utils.encryptAndHex(trojanSetting.password),
                    trojanRequest,
                    ByteBufUtil.hexDump(currentAllBytes)
                )
                super.channelRead(ctx, trojanPackage)
            }

            else -> {
                logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
                super.channelRead(ctx, msg)
            }
        }
    }
}
