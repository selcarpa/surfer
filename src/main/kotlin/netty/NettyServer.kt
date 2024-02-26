package netty


import io.jpower.kcp.netty.ChannelOptionHelper
import io.jpower.kcp.netty.UkcpChannelOption
import io.jpower.kcp.netty.UkcpServerChannel
import io.netty.bootstrap.ServerBootstrap
import io.netty.bootstrap.UkcpServerBootstrap
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.ThreadPerTaskExecutor
import model.config.Config.Configuration
import model.config.Inbound
import model.protocol.Protocol
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * netty server runner
 */
object NettyServer {
    private val logger = KotlinLogging.logger {}

    private val bossGroup: EventLoopGroup =
        NioEventLoopGroup(1, ThreadPerTaskExecutor(DefaultThreadFactory("BossGroup")))
    val workerGroup: EventLoopGroup =
        NioEventLoopGroup(0, ThreadPerTaskExecutor(DefaultThreadFactory("SurferELG")))

    //tcp
    val tcpBootstrap = ServerBootstrap().group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel::class.java)
        .handler(LoggingHandler(LogLevel.TRACE))
        .childHandler(ProxyChannelInitializer())

    //ukcp
    val ukcpServerBootstrap = UkcpServerBootstrap().group(workerGroup)
        .channel(UkcpServerChannel::class.java)
        .childHandler(ProxyChannelInitializer()).also {
            ChannelOptionHelper.nodelay(it, true, 20, 2, true)
                .childOption(UkcpChannelOption.UKCP_MTU, 512)
        }


    /**
     * start netty server
     * @param countDownLatch to wake up the blocked calling thread
     */
    fun start(countDownLatch: CountDownLatch? = null) {

        var tcpBind = false
        Optional.ofNullable(Configuration.inbounds).ifPresent {
            it.stream()
                .filter { inbound -> transmissionAssert(inbound, Protocol.TCP) }
                .forEach { inbound ->
                    tcpBootstrap.bind(inbound.port).addListener { future ->
                        if (future.isSuccess) {
                            logger.info("${inbound.protocol} bind ${inbound.port} success")
                            countDownLatch?.countDown()
                        } else {
                            logger.error("bind ${inbound.port} fail, reason:${future.cause().message}")
                            exitProcess(1)
                        }
                    }
                    tcpBind = true
                }
        }
        if (!tcpBind) {
            logger.debug { "no tcp inbound" }
        }
        Optional.ofNullable(Configuration.inbounds).ifPresent {
            //ukcp
            it.stream()
                .filter { inbound -> transmissionAssert(inbound, Protocol.UKCP) }
                .forEach { inbound ->
                    ukcpServerBootstrap.bind(inbound.port).addListener { future ->
                        if (future.isSuccess) {
                            logger.info("${inbound.protocol} bind ${inbound.port} success")
                            countDownLatch?.countDown()
                        } else {
                            logger.error("bind ${inbound.port} fail, reason:${future.cause().message}")
                            exitProcess(1)
                        }
                    }
                }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ close() }, "bye"))
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
        logger.info("我们所经历的每个日常，也或许是一系列的奇迹连续地发生！")
        if (!(bossGroup.isShutdown || bossGroup.isShuttingDown)) {
            bossGroup.shutdownGracefully()
        }
        if (!(workerGroup.isShutdown || workerGroup.isShuttingDown)) {
            workerGroup.shutdownGracefully()
        }
    }
}
