package netty.outbounds


import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.socksx.v5.*
import mu.KotlinLogging

/**
 * outbound to anywhere
 */
class GalaxyOutbound {
    companion object {
        val galaxyOutbound: GalaxyOutbound by lazy { GalaxyOutbound() }
        private val logger = KotlinLogging.logger {}

    }

    fun outbound(
        clientCTX: ChannelHandlerContext,
        msg: DefaultSocks5CommandRequest,
        socks5AddressType: Socks5AddressType,
        clientWorkGroup: EventLoopGroup
    ) {
        val bootstrap = Bootstrap()
        bootstrap.group(clientWorkGroup).channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx1: ChannelHandlerContext, msg: Any) {
                    logger.debug("id: ${ctx1.channel().id().asShortText()}, receive msg: $msg")
                    clientCTX.writeAndFlush(msg)
                }
            })
        val future: ChannelFuture = bootstrap.connect(msg.dstAddr(), msg.dstPort())
        future.addListener(object : ChannelFutureListener {
            override fun operationComplete(future1: ChannelFuture) {
                future1.addListener(ChannelFutureListener { future2 ->
                    if (future2.isSuccess) {
                        clientCTX.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg1: Any) {
                                logger.debug("id: ${ctx.channel().id().asShortText()}, receive msg1: $msg1")
                                future2.channel().writeAndFlush(msg1)
                            }
                        })
                        val commandResponse =
                            DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, socks5AddressType)
                        clientCTX.writeAndFlush(commandResponse)
                    } else {
                        logger.error(
                            "id: ${
                                clientCTX.channel().id().asShortText()
                            }, connect failure,address=${msg.dstAddr()},port=${msg.dstPort()}"
                        )
                        val commandResponse =
                            DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, socks5AddressType)
                        clientCTX.writeAndFlush(commandResponse)
                        future2.channel().close()
                    }
                })
            }
        })
    }
}
