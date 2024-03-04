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
        Configuration.inbounds.groupBy { inbound -> Protocol.valueOfOrNull(inbound.protocol).topProtocol() }.forEach {
            when (it.key) {
                Protocol.TCP -> {
                    it.value.forEach { inbound ->
                        tcpBind(inbound, { countDownLatch?.countDown() }, { exitProcess(1) })
                        tcpBind = true
                    }

                }

                Protocol.UKCP -> {
                    it.value.forEach { inbound ->
                        ukcpBind(inbound, { countDownLatch?.countDown() }, { exitProcess(1) })
                    }
                }

                else -> {
                    logger.error { "unsupported top protocol ${it.key}" }
                }
            }
        }

        if (!tcpBind) {
            logger.debug { "no tcp inbound" }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ close() }, "bye"))
    }


    /**
     * bind ukcp port
     */
    fun ukcpBind(inbound: Inbound, success: (() -> Unit)? = null, fail: (() -> Unit)? = null) {
        ukcpServerBootstrap.bind(inbound.listen, inbound.port).addListener { future ->
            if (future.isSuccess) {
                logger.info("${inbound.protocol} bind ${inbound.port} success")
                success?.let { it() }
            } else {
                logger.error("bind ${inbound.port} fail, reason:${future.cause().message}")
                fail?.let { it() }
            }
        }
    }

    /**
     * bind tcp port
     */
    fun tcpBind(inbound: Inbound, success: (() -> Unit)? = null, fail: (() -> Unit)? = null) {
        tcpBootstrap.bind(inbound.listen, inbound.port).addListener { future ->
            if (future.isSuccess) {
                logger.info("${inbound.protocol} bind ${inbound.port} success")
                success?.let { it() }
            } else {
                logger.error("bind ${inbound.port} fail, reason:${future.cause().message}")
                fail?.let { it() }
            }
        }
    }

    /**
     * assert transmission protocol
     */
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
