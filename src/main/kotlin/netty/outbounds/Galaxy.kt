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
            connectSuccess: (Channel) -> ChannelFuture,
            connectFail: () -> Unit
        ) {
            val connectListener = FutureListener<Channel> { future ->
                val outboundChannel = future.now
                if (future.isSuccess) {
                    logger.debug { "galaxy outbound to $host:$port success" }
                    connectSuccess(outboundChannel).also { channelFuture ->
                        channelFuture.addListener(ChannelFutureListener {
                            if (!it.isSuccess) {
                                logger.error(
                                    "galaxy outbound fail, id: ${
                                        it.channel().id().asShortText()
                                    }, write fail, pipelines:{}, cause ",
                                    it.channel().pipeline().names(),
                                    it.cause()
                                )
                                return@ChannelFutureListener
                            }
                            outboundChannel.pipeline().addLast(
                                RelayInboundHandler(originCTX.channel()),
                            )
                            originCTX.pipeline().addFirst(
                                RelayInboundHandler(outboundChannel),
                            )
                            logger.debug {
                                "galaxy relay established, origin id: ${
                                    originCTX.channel().id().asShortText()
                                }, pipelines: ${originCTX.channel().pipeline().names()}"
                            }
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
