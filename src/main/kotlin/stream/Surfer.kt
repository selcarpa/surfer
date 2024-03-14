package stream


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.ProxyConnectionEvent
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.*
import model.config.HttpOutboundSetting
import model.config.Outbound
import model.config.Socks5OutboundSetting
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import netty.IdleCloseHandler
import netty.ProxyChannelInitializer
import protocol.DiscardHandler
import protocol.TrojanProxy
import utils.closeOnFlush
import java.net.InetSocketAddress

private val logger = KotlinLogging.logger {}

/**
 * Commonly used relay and outbound methods
 * @param relayAndOutboundOp [RelayAndOutboundOp]
 */
fun relayAndOutbound(relayAndOutboundOp: RelayAndOutboundOp) {
    val connectListener = FutureListener<Channel> { future ->
        val outboundChannel = future.now
        if (future.isSuccess) {
            logger.trace { "outboundChannel: $outboundChannel" }
            relayAndOutboundOp.connectEstablishedCallback(outboundChannel).addListener(FutureListener {
                if (it.isSuccess) {
                    outboundChannel.pipeline()
                        .addLast(RELAY_HANDLER_NAME, RelayInboundHandler(relayAndOutboundOp.originCTX.channel()))
                    relayAndOutboundOp.originCTX.pipeline()
                        .addLast(RELAY_HANDLER_NAME, relayAndOutboundOp.originCTXRelayHandler(outboundChannel))
                    relayAndOutboundOp.afterAddRelayHandler(outboundChannel)
                } else {
                    logger.error(it.cause()) { "connectEstablishedCallback fail: ${it.cause().message}" }
                }
            })
        } else {
            logger.error(future.cause().message)
            logger.debug { future.cause().stackTrace }
            relayAndOutboundOp.connectFail()
        }
    }
    outbound(
        outbound = relayAndOutboundOp.outbound,
        connectListener = connectListener,
        eventLoopGroup = relayAndOutboundOp.originCTX.channel().eventLoop(),
        odor = relayAndOutboundOp.odor
    )
}

/**
 * relay and outbound
 */

private fun outbound(
    outbound: Outbound,
    connectListener: FutureListener<Channel>,
    odor: Odor,
    eventLoopGroup: EventLoopGroup = NioEventLoopGroup()
) {

    if (Protocol.valueOfOrNull(outbound.protocol) == Protocol.GALAXY) {
        return galaxy(connectListener, odor, eventLoopGroup)
    }
    val desProtocol = Protocol.valueOfOrNull(
        if (outbound.outboundStreamBy != null) {
            outbound.outboundStreamBy.type
        } else {
            outbound.protocol
        }
    )

    setOdorRedirect(desProtocol, outbound, odor)

    return when (desProtocol) {
        Protocol.SOCKS5 -> socks5Stream(
            connectListener, outbound.socks5Setting!!, eventLoopGroup, odor
        )

        Protocol.HTTP -> httpStream(
            connectListener, outbound.httpSetting!!, eventLoopGroup, odor
        )

        Protocol.TROJAN -> {
            trojanStream(
                connectListener,
                outbound,
                eventLoopGroup,
                odor,
                Protocol.valueOfOrNull(outbound.outboundStreamBy!!.type)
            )
        }

        else -> {
            logger.error { "stream type ${desProtocol.name} not supported" }
        }
    }
}

fun setOdorRedirect(protocol: Protocol, outbound: Outbound, odor: Odor) {
    if (outbound.serverDns) {
        odor.notDns = true
    }
    when (protocol) {

        Protocol.WSS, Protocol.WS -> {
            odor.redirectPort = outbound.outboundStreamBy!!.wsOutboundSetting!!.port
            odor.redirectHost = outbound.outboundStreamBy.wsOutboundSetting!!.host
        }

        Protocol.TCP, Protocol.TLS -> {
            odor.redirectPort = outbound.outboundStreamBy!!.tcpOutboundSetting!!.port
            odor.redirectHost = outbound.outboundStreamBy.tcpOutboundSetting!!.host
        }

        Protocol.SOCKS5 -> {
            odor.redirectPort = outbound.socks5Setting!!.port
            odor.redirectHost = outbound.socks5Setting.host
        }

        Protocol.HTTP -> {
            odor.redirectPort = outbound.httpSetting!!.port
            odor.redirectHost = outbound.httpSetting.host
        }

        else -> {
            //ignored
        }
    }
}

private fun trojanStream(
    connectListener: FutureListener<Channel>,
    outbound: Outbound,
    eventLoopGroup: EventLoopGroup,
    odor: Odor,
    streamBy: Protocol
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(TrojanProxy(outbound, odor, streamBy), PROXY_HANDLER_NAME),
            HandlerPair(ProxyChannelActiveHandler(promise), "proxy_channel_active_handler")
        )
    }, odor)

}


private fun galaxy(
    connectListener: FutureListener<Channel>, odor: Odor, eventLoopGroup: EventLoopGroup
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(BasicChannelActiveHandler(promise), "proxy_channel_active_handler")
        )
    }, odor)
}

private fun httpStream(
    connectListener: FutureListener<Channel>,
    httpOutboundSetting: HttpOutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    val proxyHandler = if (httpOutboundSetting.auth == null) {
        HttpProxyHandler(
            InetSocketAddress(
                httpOutboundSetting.host, httpOutboundSetting.port
            )
        )
    } else {
        HttpProxyHandler(
            InetSocketAddress(httpOutboundSetting.host, httpOutboundSetting.port),
            httpOutboundSetting.auth.username,
            httpOutboundSetting.auth.password
        )
    }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(proxyHandler, PROXY_HANDLER_NAME),
            HandlerPair(ProxyChannelActiveHandler(promise), "proxy_channel_active_handler")
        )
    }, odor)
}

private fun socks5Stream(
    connectListener: FutureListener<Channel>,
    socks5OutboundSetting: Socks5OutboundSetting,
    eventLoopGroup: EventLoopGroup,
    odor: Odor
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    val proxyHandler = if (socks5OutboundSetting.auth == null) {
        Socks5ProxyHandler(
            InetSocketAddress(
                socks5OutboundSetting.host, socks5OutboundSetting.port
            )
        )
    } else {
        Socks5ProxyHandler(
            InetSocketAddress(socks5OutboundSetting.host, socks5OutboundSetting.port),
            socks5OutboundSetting.auth.username,
            socks5OutboundSetting.auth.password
        )
    }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(proxyHandler, PROXY_HANDLER_NAME),
            HandlerPair(ProxyChannelActiveHandler(promise), "proxy_channel_active_handler")
        )
    }, odor)
}

private fun connect(
    eventLoopGroup: EventLoopGroup, handlerPairs: (Channel) -> MutableList<HandlerPair>, odor: Odor
) {
    val protocol = odor.desProtocol.topProtocol()
    if (protocol == Protocol.TCP) {
        connectTcp(eventLoopGroup, SurferInitializer(handlerPairs), odor.socketAddress(), odor.notDns)
    } else if (protocol == Protocol.UDP) {
        connectUdp(eventLoopGroup, SurferInitializer(handlerPairs), odor.socketAddress(), odor.notDns)
    }

}

class SurferInitializer(private val handlerPairs: (Channel) -> MutableList<HandlerPair>) :
    ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        ch.pipeline().addFirst(GLOBAL_TRAFFIC_SHAPING, ProxyChannelInitializer.globalTrafficShapingHandler)
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IDLE_CLOSE_HANDLER, IdleCloseHandler())
        ch.pipeline().addFirst(IDLE_CHECK_HANDLER, IdleStateHandler(300, 300, 300))
        handlerPairs(ch).forEach {
            ch.pipeline().addLast(it.name, it.handler)
        }

    }
}

private fun connectTcp(
    eventLoopGroup: EventLoopGroup,
    channelInitializer: ChannelInitializer<Channel>,
    socketAddress: InetSocketAddress,
    notDns: Boolean = false
) {
    Bootstrap().group(eventLoopGroup).also {
        if (!notDns) {
            it.disableResolver()
        }
    }.channel(NioSocketChannel::class.java).handler(LoggingHandler(LogLevel.TRACE)).handler(channelInitializer)
        .connect(socketAddress)
}

private fun connectUdp(
    eventLoopGroup: EventLoopGroup,
    channelInitializer: ChannelInitializer<Channel>,
    socketAddress: InetSocketAddress,
    notDns: Boolean = false
) {
    Bootstrap().group(eventLoopGroup).also {
        if (!notDns) {
            it.disableResolver()
        }
    }.channel(NioDatagramChannel::class.java).handler(LoggingHandler(LogLevel.TRACE)).handler(channelInitializer)
        .connect(socketAddress)
}

/**
 * basic activator for client connected, some protocol not have complex handshake process, so we can use this activator to active other operation
 */
class BasicChannelActiveHandler(private val promise: Promise<Channel>) : ChannelDuplexHandler() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        super.channelActive(ctx)
        promise.setSuccess(ctx.channel())
        ctx.pipeline().remove(this)
    }
}

/**
 * proxy activator for client connected, when proxy handshake complete, we can activate other operation
 */
class ProxyChannelActiveHandler(private val promise: Promise<Channel>) : ChannelDuplexHandler() {
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is ProxyConnectionEvent) {
            logger.trace { "[${ctx.channel().id().asShortText()}] triggered ProxyConnectionEvent: $evt" }
            promise.setSuccess(ctx.channel())
            ctx.pipeline().remove(this)
        }
        super.userEventTriggered(ctx, evt)
    }
}


/**
 * relay from client channel to server
 */
class RelayInboundHandler(private val relayChannel: Channel, private val inActiveCallBack: () -> Unit = {}) :
    ChannelInboundHandlerAdapter() {

    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (relayChannel.isActive) {
            logger.trace {
                "relay inbound read from [${ctx.channel().id().asShortText()}] pipeline handlers:${
                    ctx.channel().pipeline().names()
                }, to [${relayChannel.id().asShortText()}] pipeline handlers:${
                    relayChannel.pipeline().names()
                }, write message:${msg.javaClass.name}"
            }
            relayChannel.writeAndFlush(msg).addListener(ChannelFutureListener {
                if (!it.isSuccess) {
                    logger.error(it.cause()) {
                        "relay inbound write message: ${msg.javaClass.name} to [${
                            relayChannel.id().asShortText()
                        }] failed, cause: ${it.cause().message}"
                    }
                }
            })
        } else {
            logger.warn {
                "[${
                    relayChannel.id().asShortText()
                }] relay channel is not active, close message:${msg.javaClass.name}"
            }
            ReferenceCountUtil.release(msg)
            ctx.channel().closeOnFlush()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (relayChannel.isActive) {
            logger.debug {
                "[${ctx.channel().id().asShortText()}] closed channel, write close to relay channel [${
                    relayChannel.id().asShortText()
                }]"
            }
            relayChannel.pipeline().remove(RELAY_HANDLER_NAME)
            //add a discard handler to discard all message
            relayChannel.pipeline().addLast(DiscardHandler())
            relayChannel.close()
            inActiveCallBack()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(cause) {
            "relay inbound handler exception caught, [${ctx.channel().id()}], pipeline: ${
                ctx.channel().pipeline().names()
            }, message: ${cause.message}"
        }
        ctx.close()
    }
}

data class HandlerPair(val handler: ChannelHandler, val name: String)
