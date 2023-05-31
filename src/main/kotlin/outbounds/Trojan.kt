package outbounds


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.util.ReferenceCountUtil
import model.config.Outbound
import model.config.TrojanSetting
import model.protocol.ConnectTo
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import stream.RelayInboundHandler
import utils.Sha224Utils

class TrojanRelayInboundHandler(
    relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest,
    inActiveCallBack: () -> Unit = {},
    private var firstPackage: Boolean = true
) : RelayInboundHandler(relayChannel, inActiveCallBack) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    constructor(
        outboundChannel: Channel, outbound: Outbound, connectTo: ConnectTo, firstPackage: Boolean = false
    ) : this(
        outboundChannel, outbound.trojanSetting!!, TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(),
            connectTo.addressType().byteValue(),
            connectTo.address,
            connectTo.port
        ), firstPackage = firstPackage
    )

    /**
     * Trojan protocol only need package once, then send origin data directly
     */

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (firstPackage) {
            when (msg) {
                is ByteBuf -> {
                    val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
                    ReferenceCountUtil.release(msg)
                    super.channelRead(ctx, TrojanPackage.toByteBuf(trojanPackage))
                    firstPackage = false
                }

                else -> {
                    logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
                    super.channelRead(ctx, msg)
                }
            }
        } else {
            super.channelRead(ctx, msg)
        }
    }

}

fun byteBuf2TrojanPackage(msg: ByteBuf, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage {
    val currentAllBytes = ByteArray(msg.readableBytes())
    msg.readBytes(currentAllBytes)
    return TrojanPackage(
        Sha224Utils.encryptAndHex(trojanSetting.password), trojanRequest, ByteBufUtil.hexDump(currentAllBytes)
    )
}
