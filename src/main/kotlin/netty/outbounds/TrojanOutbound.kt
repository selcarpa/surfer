package netty.outbounds

import io.klogging.NoCoLogging
import io.netty.channel.*
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import model.config.TrojanSetting
import utils.Sha224

class TrojanOutbound : NoCoLogging {
    companion object {
        fun outbound(
            clientChannel: Channel,
            msg: DefaultSocks5CommandRequest,
            socks5AddressType: Socks5AddressType,
            clientWorkGroup: EventLoopGroup,
            serverChannel: Channel,
            trojanSetting: TrojanSetting
        ) {
            serverChannel.pipeline().addFirst(TrojanOutboundHandler(trojanSetting))
        }

    }

}

class TrojanOutboundHandler(private val trojanSetting: TrojanSetting) : ChannelOutboundHandlerAdapter(), NoCoLogging {
    override fun write(ctx: ChannelHandlerContext?, msg: Any?, promise: ChannelPromise?) {
        when (msg) {
            is DefaultSocks5CommandRequest -> {
                val content: StringBuilder = StringBuilder()
                val p1 = Sha224.encryptAndHex(trojanSetting.password)
                logger.debug("p1: $p1")
                content.append(p1)
                content.append("\r\n")
                content.append("01")
                content.append("01")
                content.append(msg.dstAddr())
                content.append(msg.dstPort())
                content.append("\r\n")
//                content.append(msg.)
            }
        }
    }
}
