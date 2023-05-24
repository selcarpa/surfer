package outbounds

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import model.config.Outbound
import model.protocol.ConnectTo

interface Outbound {
    fun outbound(
        originCTX: ChannelHandlerContext,
        outbound: Outbound,
        connectTo: ConnectTo,
        connectSuccess: (Channel) -> ChannelFuture,
        afterAddRelayHandler: (Channel) -> Unit,
        connectFail: () -> Unit,
    )

}
