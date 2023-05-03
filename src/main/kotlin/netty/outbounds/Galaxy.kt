package netty.outbounds


import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import io.netty.util.concurrent.FutureListener
import model.config.Outbound
import mu.KotlinLogging
import netty.stream.RelayInboundHandler

/**
 * outbound to anywhere
 */
class GalaxyOutbound {
    companion object {
        private val logger = KotlinLogging.logger {}
        fun outbound(
            originCTX: ChannelHandlerContext,
            outbound: Outbound,
            relayOutBoundHandler: RelayInboundHandler,
            connectSuccess: () -> ChannelFuture,
            connectFail: () -> Unit
        ) {
            val connectListener = FutureListener<Channel?> { future ->
                val outboundChannel = future.now!!
                if (future.isSuccess) {
                    connectSuccess().also { channelFuture ->
                        channelFuture.addListener(ChannelFutureListener {
                            outboundChannel.pipeline().addLast(
                                relayOutBoundHandler
                            )
                            originCTX.pipeline().addLast(
                                RelayInboundHandler(outboundChannel),
                            )
                        })
                    }
                } else {
                    connectFail()
                }
            }
            galaxy(connectListener)

        }
        private fun galaxy(connectListener: FutureListener<Channel?>) {
            val bootstrap = Bootstrap()
            val eventLoop = NioEventLoopGroup()
            val promise = eventLoop.next().newPromise<Channel>()
            promise.addListener(connectListener)
            bootstrap.group(eventLoop).channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(ctx1: ChannelHandlerContext, msg: Any) {
                        logger.debug("id: {}, receive msg: {}", ctx1.channel().id().asShortText(), msg)
                        promise.setSuccess(ctx1.channel())
                    }
                })
        }
    }
}
