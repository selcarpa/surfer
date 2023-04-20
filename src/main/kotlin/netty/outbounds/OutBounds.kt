package netty.outbounds

import io.klogging.NoCoLogging
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import model.config.ProxyProtocolSetting
import model.config.TrojanSetting

class OutBounds {
    companion object : NoCoLogging {
        fun outbound(
            clientChannel: Channel,
            msg: DefaultSocks5CommandRequest,
            socks5AddressType: Socks5AddressType,
            clientWorkGroup: EventLoopGroup,
            serverChannel: Channel,
            proxyProtocolSetting: ProxyProtocolSetting?
        ) {
            when (proxyProtocolSetting) {
                is TrojanSetting -> {
                    TrojanOutbound.outbound(
                        clientChannel,
                        msg,
                        socks5AddressType,
                        clientWorkGroup,
                        serverChannel,
                        proxyProtocolSetting as TrojanSetting
                    )
                }

                else -> {
                    logger.error("not support outbound")
                }
            }
        }
    }
}
