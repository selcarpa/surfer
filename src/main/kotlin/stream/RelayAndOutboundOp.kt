package stream

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import model.config.Outbound
import model.protocol.Odor
import utils.closeOnFlush

/**
 * Abstract Relay and outbound operation, including some necessary parameters for the operation
 */
data class RelayAndOutboundOp(
    val originCTX: ChannelHandlerContext, val outbound: Outbound, val odor: Odor
) {
    /**
     * relay handler for origin channel, override it when it is necessary
     */
    var originCTXRelayHandler: (Channel) -> RelayInboundHandler = { RelayInboundHandler(it) }


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
        originCTX.channel().closeOnFlush()
    };


}
