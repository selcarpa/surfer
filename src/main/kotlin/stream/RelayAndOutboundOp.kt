package stream

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import model.config.Outbound
import model.protocol.ConnectTo
import utils.ChannelUtils

/**
 * Abstract Relay and outbound operation, including some necessary parameters for the operation
 */
data class RelayAndOutboundOp(
    val originCTX: ChannelHandlerContext, val outbound: Outbound, val connectTo: ConnectTo
) {
    /**
     * relay handler for origin channel, override it when it is necessary
     */
    var originCTXRelayHandler: (Channel) -> RelayInboundHandler = { RelayInboundHandler(it) }
        set(value) {
            field = value
            overrideRelayHandler = true
        }

    /**
     * when overrideRelayHandler is false, in [relayAndOutbound] method, when some protocol set, will override the relay handler,
     * for example, trojan protocol will override the relay handler to a [protocol.TrojanRelayInboundHandler]
     */
    var overrideRelayHandler = false

    /**
     * when connect established, do something
     */
    var connectEstablishedCallback: (Channel) -> ChannelFuture = { it.newPromise().setSuccess() };

    /**
     * when add relay handler to origin channel, do something
     */
    var afterAddRelayHandler: (Channel) -> Unit = {}

    /**
     * when connect failed, do something
     */
    var connectFail: () -> Unit = {
        //while connect failed, write failure response to client, and close the connection
        ChannelUtils.closeOnFlush(originCTX.channel())
    };


}
