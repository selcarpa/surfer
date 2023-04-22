package netty.outbounds

import io.klogging.NoCoLogging
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.util.ReferenceCountUtil
import model.config.TrojanSetting
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import netty.stream.RelayHandler
import utils.BigEndianUtils
import utils.Sha224Utils
import kotlin.math.log

class TrojanEncoder : MessageToByteEncoder<TrojanPackage>(), NoCoLogging {
    override fun encode(ctx: ChannelHandlerContext?, msg: TrojanPackage, out: ByteBuf) {
        out.writeBytes(ByteBufUtil.decodeHexDump(msg.hexSha224Password))
        out.writeBytes(ByteBufUtil.decodeHexDump("0d0a"))
        out.writeBytes(msg.request.cmd.byteValue())
        out.writeBytes(msg.request.atyp.byteValue())
        out.writeBytes(msg.request.host.toByteArray())
        out.writeBytes(BigEndianUtils.int2ByteArrayTrimZero(msg.request.port, 2))
    }
}

private fun ByteBuf.writeBytes(byteValue: Byte) {
    //convert to a array
    val bytes = ByteArray(1)
    bytes[0] = byteValue
    this.writeBytes(bytes)
}

class TrojanRelayHandler(
    relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest
) :
    RelayHandler(relayChannel), NoCoLogging {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        logger.debug("TrojanRelayHandler receive message:${msg.javaClass.name}")
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
            }
        }
    }
}
