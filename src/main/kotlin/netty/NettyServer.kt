package netty


import io.netty5.bootstrap.ServerBootstrap
import io.netty5.channel.EventLoopGroup
import io.netty5.channel.MultithreadEventLoopGroup
import io.netty5.channel.SingleThreadEventLoop
import io.netty5.channel.nio.NioHandler
import io.netty5.channel.socket.nio.NioServerSocketChannel
import io.netty5.handler.logging.BufferFormat
import io.netty5.handler.logging.LogLevel
import io.netty5.handler.logging.LoggingHandler
import io.netty5.util.concurrent.DefaultThreadFactory
import io.netty5.util.concurrent.ThreadPerTaskExecutor
import model.config.Config.Configuration
import model.config.Inbound
import model.protocol.Protocol
import mu.KotlinLogging
import java.util.*
import kotlin.system.exitProcess


/**
 * netty server runner
 */
object NettyServer {
    private val logger = KotlinLogging.logger {}

    private val bossGroup:EventLoopGroup= SingleThreadEventLoop(
        ThreadPerTaskExecutor(
            DefaultThreadFactory(
                "BossGroup", Thread.MAX_PRIORITY
            )
        ), NioHandler.newFactory().newHandler()
    )
    private val workerGroup: EventLoopGroup = MultithreadEventLoopGroup(
        ThreadPerTaskExecutor(
            DefaultThreadFactory(
                "SurferELG", Thread.MAX_PRIORITY
            )
        ), NioHandler.newFactory()
    )
    private var tcpBind: Boolean = false
    fun start() {
        //tcp
        val tcpBootstrap = ServerBootstrap().group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .handler(LoggingHandler(LogLevel.TRACE, BufferFormat.SIMPLE))
            .childHandler(ProxyChannelInitializer())
        Optional.ofNullable(Configuration.inbounds).ifPresent {
            it.stream()
                .filter { inbound -> transmissionAssert(inbound, Protocol.TCP) }
                .forEach { inbound ->
                    tcpBootstrap.bind(inbound.port).addListener { future ->
                        if (future.isSuccess) {
                            logger.info("${inbound.protocol} bind ${inbound.port} success")
                            Runtime.getRuntime().addShutdownHook(Thread({ close() }, "Server Shutdown Thread"))
                        } else {
                            logger.error("bind ${inbound.port} fail, reason:{}", future.cause().message)
                            exitProcess(1)
                        }
                    }
                    tcpBind = true
                }
        }
        if (!tcpBind) {
            logger.debug { "no tcp inbound" }
        }
    }

    private fun transmissionAssert(inbound: Inbound, desProtocol: Protocol): Boolean {
        var protocol = Protocol.valueOfOrNull(inbound.protocol).topProtocol()
        if (protocol == desProtocol) {
            return true
        }
        if (inbound.inboundStreamBy != null) {
            protocol = Protocol.valueOfOrNull(inbound.inboundStreamBy.type)
            protocol = protocol.topProtocol()
            if (protocol == desProtocol) {
                return true
            }
        }
        return false
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
