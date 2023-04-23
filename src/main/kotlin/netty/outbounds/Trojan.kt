package netty.outbounds

import io.klogging.NoCoLogging
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.util.ReferenceCountUtil
import model.config.TrojanSetting
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import netty.stream.RelayHandler
import utils.BigEndianUtils
import utils.Sha224Utils

class TrojanOutbound : ChannelOutboundHandlerAdapter(), NoCoLogging {

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        when (msg) {
            is TrojanPackage -> {
                logger.debug("TrojanEncoder encode message:${msg.javaClass.name}")
                val h1 = ByteBufUtil.decodeHexDump(msg.hexSha224Password)
                val h2 = ByteBufUtil.decodeHexDump("0d0a")
                val h3 = msg.request.cmd.byteValue()
                val h4 = msg.request.atyp.byteValue()
                val h5 = msg.request.host.toByteArray()
                val h6 = BigEndianUtils.int2ByteArrayTrimZero(msg.request.port, 2)
                val out = ByteBufUtil.threadLocalDirectBuffer()
                out.writeBytes(h1)
                out.writeBytes(h2)
                out.writeBytes(h3)
                out.writeBytes(h4)
                out.writeBytes(h5)
                out.writeBytes(h6)
                val binaryWebSocketFrame = BinaryWebSocketFrame(out)
                ctx.writeAndFlush(binaryWebSocketFrame)
            }

            else -> {
                ctx.writeAndFlush(msg)
            }
        }
    }
}

private fun ByteBuf.writeBytes(byteValue: Byte) {
    val bytes = ByteArray(1)
    bytes[0] = byteValue
    this.writeBytes(bytes)
}

class TrojanRelayHandler(
    private val relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest
) : RelayHandler(relayChannel), NoCoLogging {
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
                if (relayChannel.isActive) {
                    logger.debug(
                        "${ctx.channel().id().asShortText()} pipeline handlers:${
                            ctx.pipeline().names()
                        }, write message:${msg.javaClass.name}"
                    )
                    relayChannel.writeAndFlush(trojanPackage)
                    logger.debug("relayChannel handlers: ${relayChannel.pipeline().names()}")
                }
            }

            else -> {
                logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
            }
        }
    }
}
