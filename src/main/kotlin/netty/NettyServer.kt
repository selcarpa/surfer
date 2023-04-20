package netty

import io.klogging.NoCoLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.Future
import model.config.ConfigurationHolder

/**
 * netty服务端配置
 */
class NettyServer : NoCoLogging {
    private val bossGroup: EventLoopGroup = NioEventLoopGroup()
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()
    fun start() {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup) // 绑定线程池
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(LoggingHandler(LogLevel.DEBUG))
            .childHandler(ProxyChannelInitializer())
            .option(ChannelOption.SO_BACKLOG, 65536)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
        bootstrap.bind(ConfigurationHolder.configuration.inbounds[0].port).addListener { future: Future<in Void?> ->
            if (future.isSuccess) {
                logger.info("server bind success")
                Runtime.getRuntime().addShutdownHook(Thread({ close() }, "Server Shutdown Thread"))
            } else {
                logger.error("server bind fail, reason:{}", future.cause().message)
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
