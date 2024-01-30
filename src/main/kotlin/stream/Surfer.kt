package stream


import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.ProxyConnectionEvent
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import io.netty.util.concurrent.Promise
import model.PROXY_HANDLER_NAME
import model.RELAY_HANDLER_NAME
import model.config.HttpOutboundSetting
import model.config.Outbound
import model.config.Sock5OutboundSetting
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import netty.IdleCloseHandler
import protocol.DiscardHandler
import protocol.TrojanProxy
import utils.ChannelUtils
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

    setOdorRedirect(outbound, odor)

    return when (Protocol.valueOfOrNull(outbound.protocol)) {
        Protocol.SOCKS5 -> socks5Stream(
            connectListener, outbound.sock5Setting!!, eventLoopGroup, odor
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
            logger.error { "stream type ${outbound.outboundStreamBy!!.type} not supported" }

        }
    }
}

fun setOdorRedirect(outbound: Outbound, odor: Odor) {
    if (outbound.serverDns) {
        odor.notDns = true
    }
    when (Protocol.valueOfOrNull(outbound.outboundStreamBy!!.type)) {

        Protocol.WSS, Protocol.WS -> {
            odor.redirectPort = outbound.outboundStreamBy.wsOutboundSetting!!.port
            odor.redirectHost = outbound.outboundStreamBy.wsOutboundSetting.host
        }

        Protocol.TCP, Protocol.TLS -> {
            odor.redirectPort = outbound.outboundStreamBy.tcpOutboundSetting!!.port
            odor.redirectHost = outbound.outboundStreamBy.tcpOutboundSetting.host
        }

        Protocol.SOCKS5 -> {
            odor.redirectPort = outbound.sock5Setting!!.port
            odor.redirectHost = outbound.sock5Setting.host
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
            HandlerPair(ChannelActiveHandler(promise))
        )
    }, odor)

}


private fun galaxy(
    connectListener: FutureListener<Channel>, odor: Odor, eventLoopGroup: EventLoopGroup
) {
    val promise = eventLoopGroup.next().newPromise<Channel>().also { it.addListener(connectListener) }
    connect(eventLoopGroup, {
        mutableListOf(
            HandlerPair(ChannelActiveHandler(promise))
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
            HandlerPair(ChannelActiveHandler(promise))
        )
    }, odor)
}

private fun socks5Stream(
    connectListener: FutureListener<Channel>,
    socks5OutboundSetting: Sock5OutboundSetting,
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
            HandlerPair(ChannelActiveHandler(promise))
        )
    }, odor)
}

private fun connect(
    eventLoopGroup: EventLoopGroup,
    handlerPairs: (Channel) -> MutableList<HandlerPair>,
    odor: Odor
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
        //todo: set idle timeout, and close channel
        ch.pipeline().addFirst(IdleStateHandler(300, 300, 300))
        ch.pipeline().addFirst(IdleCloseHandler())
        handlerPairs(ch).forEach {
            if (it.name != null) {
                ch.pipeline().addLast(it.name, it.handler)
            } else {
                ch.pipeline().addLast(it.handler)
            }
        }

    }
}

private fun connectTcp(
    eventLoopGroup: EventLoopGroup,
    channelInitializer: ChannelInitializer<Channel>,
    socketAddress: InetSocketAddress,
    notDns: Boolean = false
) {
    Bootstrap().group(eventLoopGroup)
        .also {
            if (!notDns) {
                it.disableResolver()
            }
        }
        .channel(NioSocketChannel::class.java)
        .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
        .handler(channelInitializer)
        .connect(socketAddress)
}

private fun connectUdp(
    eventLoopGroup: EventLoopGroup,
    channelInitializer: ChannelInitializer<Channel>,
    socketAddress: InetSocketAddress,
    notDns: Boolean = false
) {
    Bootstrap().group(eventLoopGroup)
        .also {
            if (!notDns) {
                it.disableResolver()
            }
        }
        .channel(NioDatagramChannel::class.java)
        .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
        .handler(channelInitializer)
        .connect(socketAddress)
}

/**
 * basic activator for client connected, some protocol not have complex handshake process, so we can use this activator to active other operation
 */
class ChannelActiveHandler(private val promise: Promise<Channel>) : ChannelDuplexHandler() {
    private val logger = KotlinLogging.logger {}
    override fun channelActive(ctx: ChannelHandlerContext) {
        super.channelActive(ctx)
        promise.setSuccess(ctx.channel())
        ctx.pipeline().remove(this)
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any?) {
        if (evt is ProxyConnectionEvent) {
            logger.trace { "ProxyConnectionEvent: $evt" }
        }
        ctx.fireUserEventTriggered(evt)
    }
}


/**
 * relay from client channel to server
 */
class RelayInboundHandler(private val relayChannel: Channel, private val inActiveCallBack: () -> Unit = {}) :
    ChannelInboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (relayChannel.isActive) {
            logger.trace(
                "relay inbound read from [{}] pipeline handlers:{}, to [{}] pipeline handlers:{}, write message:{}",
                ctx.channel().id().asShortText(),
                ctx.channel().pipeline().names(),
                relayChannel.id().asShortText(),
                relayChannel.pipeline().names(),
                msg.javaClass.name
            )
            relayChannel.writeAndFlush(msg).addListener(ChannelFutureListener {
                if (!it.isSuccess) {
                    logger.error(
                        "relay inbound write message:${msg.javaClass.name} to [${
                            relayChannel.id().asShortText()
                        }] failed, cause: ${it.cause().message}", it.cause()
                    )
                }
            })
        } else {
            logger.warn(
                "[${
                    relayChannel.id().asShortText()
                }] relay channel is not active, close message:${msg.javaClass.name}"
            )
            ReferenceCountUtil.release(msg)
            ChannelUtils.closeOnFlush(ctx.channel())
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        if (relayChannel.isActive) {
            logger.debug(
                "[{}] closed channel, write close to relay channel [{}]",
                ctx.channel().id().asShortText(),
                relayChannel.id().asShortText()
            )
            relayChannel.pipeline().remove(RELAY_HANDLER_NAME)
            //add a discard handler to discard all message
            relayChannel.pipeline().addLast(DiscardHandler())
            relayChannel.close()
            inActiveCallBack()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.error(
            "relay inbound handler exception caught, [${ctx.channel().id()}], pipeline: ${
                ctx.channel().pipeline().names()
            }, message: ${cause.message}", cause
        )
        ctx.close()
    }
}

data class HandlerPair(val handler: ChannelHandler, val name: String?) {
    constructor(handler: ChannelHandler) : this(handler, null)
}
