package netty.outbounds


import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.util.concurrent.FutureListener
import model.config.Outbound
import mu.KotlinLogging
import netty.stream.RelayInboundHandler
import netty.stream.Surfer
import java.net.InetSocketAddress

/**
 * outbound to anywhere
 */
class GalaxyOutbound {
    companion object {
        private val logger = KotlinLogging.logger {}
        fun outbound(
            originCTX: ChannelHandlerContext,
            outbound: Outbound,
            host: String,
            port: Int,
            connectSuccess: () -> ChannelFuture,
            connectFail: () -> Unit
        ) {
            val connectListener = FutureListener<Channel?> { future ->
                val outboundChannel = future.now!!
                if (future.isSuccess) {
                    connectSuccess().also { channelFuture ->
                        channelFuture.addListener(ChannelFutureListener {
                            outboundChannel.pipeline().addLast(
                                RelayInboundHandler(originCTX.channel()),
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
            Surfer.outbound(
                outbound, connectListener, InetSocketAddress(host, port)
            )

        }
    }
}
