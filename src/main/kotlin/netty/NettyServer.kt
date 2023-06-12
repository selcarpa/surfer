package netty


import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import model.config.ConfigurationSettings.Companion.Configuration
import mu.KotlinLogging
import java.util.*
import kotlin.system.exitProcess

/**
 * netty server runner
 */
object NettyServer {
    private val logger = KotlinLogging.logger {}

    private val bossGroup: EventLoopGroup = NioEventLoopGroup()
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()
    fun start() {
        val bootstrap = ServerBootstrap().group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
            .childHandler(ProxyChannelInitializer())
        Optional.ofNullable(Configuration.inbounds).ifPresent {
            it.stream().forEach { inbound ->
                bootstrap.bind(inbound.port).addListener { future ->
                    if (future.isSuccess) {
                        logger.info("${inbound.protocol} bind ${inbound.port} success")
                        Runtime.getRuntime().addShutdownHook(Thread({ close() }, "Server Shutdown Thread"))
                    } else {
                        logger.error("bind ${inbound.port} fail, reason:{}", future.cause().message)
                        exitProcess(1)
                    }
                }
            }
        }

    }

    /**
     * close gracefully
     */
    private fun close() {
        if (!(bossGroup.isShutdown || bossGroup.isShuttingDown)) {
            bossGroup.shutdownGracefully()
        }
        if (!(workerGroup.isShutdown || workerGroup.isShuttingDown)) {
            workerGroup.shutdownGracefully()
        }
    }
}
