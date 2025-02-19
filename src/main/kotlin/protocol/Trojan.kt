package protocol


import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.*
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.proxy.ProxyHandler
import io.netty.handler.ssl.SslCompletionEvent
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.ReferenceCountUtil
import model.RELAY_HANDLER_NAME
import model.TROJAN_PROXY_OUTBOUND
import model.config.Outbound
import model.config.OutboundStreamBy
import model.config.TrojanSetting
import model.protocol.Odor
import model.protocol.Protocol
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import netty.ActiveAutoExecHandler
import netty.ExceptionCaughtHandler
import stream.WebSocketDuplexHandler
import utils.toAddressType
import utils.toSha224
import utils.toUUid
import java.net.InetSocketAddress
import java.net.URI

private val logger = KotlinLogging.logger { }

class TrojanOutboundHandler(
    private val trojanSetting: TrojanSetting, private val trojanRequest: TrojanRequest
) : ChannelDuplexHandler() {

    private var handshaked = false

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (handshaked) {
            super.write(ctx, msg, promise)
            return
        }
        if (msg is ByteBuf) {
            val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
            ReferenceCountUtil.release(msg)
            val trojanByteBuf = TrojanPackage.toByteBuf(trojanPackage)
            handshaked = !handshaked
            super.write(ctx, trojanByteBuf, promise)
            return
        }
        super.write(ctx, msg, promise)
    }


}

/**
 * convert ByteBuf to TrojanPackage
 */
fun byteBuf2TrojanPackage(msg: ByteBuf, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage =
    TrojanPackage(
        trojanSetting.password.toUUid().toString().toSha224(), trojanRequest, ByteBufUtil.hexDump(msg)
    )


class TrojanProxy(
    private val socketAddress: InetSocketAddress,
    private val outboundStreamBy: OutboundStreamBy,
    trojanSetting: TrojanSetting,
    trojanRequest: TrojanRequest,
    private val streamBy: Protocol,
) : ProxyHandler(socketAddress) {
    companion object {
        private val setConnectSuccess =
            ProxyHandler::class.java.declaredMethods.find { it.name == "setConnectSuccess" }!!.also {
                it.isAccessible = true
            }
    }

    private val setThisConnectSuccess = {
        setConnectSuccess.invoke(this)
    }

    private lateinit var doFirstSend: () -> Unit

    constructor(outbound: Outbound, odor: Odor, streamBy: Protocol) : this(
        InetSocketAddress(odor.redirectHost, odor.redirectPort!!),
        outbound.outboundStreamBy!!,
        outbound.trojanSetting!!,
        TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(), odor.host.toAddressType().byteValue(), odor.host, odor.port
        ),
        streamBy
    )

    private val trojanOutboundHandler = TrojanOutboundHandler(trojanSetting, trojanRequest)
    override fun protocol(): String {
        return Protocol.TROJAN.name
    }

    override fun authScheme(): String {
        return "default-auth-scheme"
    }


    override fun addCodec(ctx: ChannelHandlerContext) {
        val p = ctx.pipeline()
        val name = ctx.name()

        when (streamBy) {
            //when just a plain tcp connect, we just open the tcp connect and add trojan outbound handler
            Protocol.TCP -> {
                addPreHandledTcp(ctx)
                p.addBefore(name, TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
                p.addLast("AutoSuccessHandler", ActiveAutoExecHandler { setThisConnectSuccess() })
            }

            Protocol.TLS -> {
                addPreHandledTls(ctx)
            }

            Protocol.WS -> {
                addPreHandledWs(ctx)
            }

            Protocol.WSS -> {
                addPreHandledTls(ctx)
                addPreHandledWs(ctx)
            }

            else -> throw IllegalArgumentException("unsupported stream")
        }


    }

    /**
     * add websocket handler before trojan outbound handler
     */
    private fun addPreHandledWs(ctx: ChannelHandlerContext) {
        val newHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
            URI(
                "${
                    if (streamBy == Protocol.WS) {
                        "ws"
                    } else {
                        "wss"
                    }
                }://${outboundStreamBy.wsOutboundSetting!!.host}:${outboundStreamBy.wsOutboundSetting.port}/${
                    outboundStreamBy.wsOutboundSetting.path.removePrefix(
                        "/"
                    )
                }"
            ), WebSocketVersion.V13, null, true, DefaultHttpHeaders()
        )
        val newPromise = ctx.channel().eventLoop().newPromise<Channel>()
        val embeddedChannel = EmbeddedChannel()
        newPromise.addListener {
            if (it.isSuccess) {
                setThisConnectSuccess()
                embeddedChannel.pipeline().addLast(TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
            }
        }
        //set internal trojan outbound pipes
        embeddedChannel.pipeline().addLast("HttpClientCodec", HttpClientCodec())
        embeddedChannel.pipeline().addLast("HttpObjectAggregator", HttpObjectAggregator(65536))
        embeddedChannel.pipeline()
            .addLast("WebSocketClientCompressionHandler", WebSocketClientCompressionHandler.INSTANCE)
        embeddedChannel.pipeline().addLast(
            "WebSocketHandshakeHandler", object : SimpleChannelInboundHandler<FullHttpResponse>() {
                override fun channelRead0(ctx2: ChannelHandlerContext, msg: FullHttpResponse) {
                    if (!newHandshaker.isHandshakeComplete) {
                        newHandshaker.finishHandshake(ctx2.channel(), msg)
                        ctx2.fireUserEventTriggered(
                            WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
                        )
                        ctx2.pipeline().remove(this)
                        return
                    }
                }
            })
        embeddedChannel.pipeline().addLast("websocket_duplex_handler", WebSocketDuplexHandler(newPromise))
        embeddedChannel.pipeline().addFirst("$RELAY_HANDLER_NAME-embedded-outbound", RelayOutBoundHandler1(ctx))
        embeddedChannel.pipeline().addLast("$RELAY_HANDLER_NAME-embedded-inbound", RelayInBoundHandler2(ctx))
        embeddedChannel.pipeline().addLast(ExceptionCaughtHandler())


        ctx.pipeline().addBefore(ctx.name(), "$RELAY_HANDLER_NAME-embedded-inbound", RelayInBoundHandler1(embeddedChannel))
        ctx.pipeline().addAfter(ctx.name(), "$RELAY_HANDLER_NAME-embedded-outbound", RelayOutBoundHandler2(embeddedChannel))
        ctx .pipeline().addLast(ExceptionCaughtHandler())

        doFirstSend = {
            newHandshaker.handshake(embeddedChannel)
        }

    }

    /**
     * add tls handler before trojan outbound handler
     */
    private fun addPreHandledTls(ctx: ChannelHandlerContext) {
        val sslCtx: SslContext =
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
        ctx.pipeline().addBefore(
            ctx.name(), "ssl", sslCtx.newHandler(ctx.channel().alloc(), socketAddress.hostName, socketAddress.port)
        )
        ctx.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                if (evt is SslCompletionEvent) {
                    ctx.pipeline().addBefore(ctx.name(), TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
                    setThisConnectSuccess()
                    ctx.pipeline().remove(this)
                    return
                }
                super.userEventTriggered(ctx, evt)
            }
        })

    }


    /**
     * tcp stream needn't any handler
     */
    @Suppress("UNUSED_PARAMETER")
    private fun addPreHandledTcp(ctx: ChannelHandlerContext) {
        //ignored
    }

    override fun removeEncoder(ctx: ChannelHandlerContext) {
        //ignored
    }

    override fun removeDecoder(ctx: ChannelHandlerContext) {
        //ignored
    }

    override fun newInitialMessage(ctx: ChannelHandlerContext): Any? {
        //ignored
        doFirstSend()
        return null
    }

    override fun handleResponse(ctx: ChannelHandlerContext, response: Any): Boolean {
        return true
    }
}

/**
 * relay from client channel to server
 */
class RelayOutBoundHandler1(private val channelHandlerContext: ChannelHandlerContext) :
    ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        channelHandlerContext.writeAndFlush(msg, channelHandlerContext.newPromise().addListener {
            if (it.isSuccess) {
                promise.setSuccess()
            } else {
                promise.setFailure(it.cause())
            }
        })
    }
}
/**
 * relay from client channel to server
 */
class RelayOutBoundHandler2(private val channel: EmbeddedChannel) :
    ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        channel.writeOutbound(msg)
    }
}

/**
 * relay from client channel to server
 */
class RelayInBoundHandler1(private val channel: EmbeddedChannel) :
    ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        channel.writeInbound(msg)
    }
}


/**
 * relay from client channel to server
 */
class RelayInBoundHandler2(private val channelHandlerContext: ChannelHandlerContext) :
    ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        channelHandlerContext.fireChannelRead(msg)
    }
}

