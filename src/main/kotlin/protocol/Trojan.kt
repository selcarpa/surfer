package protocol


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.*
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.proxy.ProxyHandler
import io.netty.handler.ssl.SslCompletionEvent
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.ReferenceCountUtil
import model.TROJAN_PROXY_OUTBOUND
import model.config.Outbound
import model.config.OutboundStreamBy
import model.config.TrojanSetting
import model.protocol.Odor
import model.protocol.Protocol
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import netty.AutoExecHandler
import netty.ExceptionCaughtHandler
import stream.WebSocketDuplexHandler
import stream.WebSocketHandshakeHandler
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

//    constructor(
//        outbound: Outbound, odor: Odor
//    ) : this(
//        outbound.trojanSetting!!, TrojanRequest(
//            Socks5CommandType.CONNECT.byteValue(), odor.addressType().byteValue(), odor.host, odor.port
//        )
//    )

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

    private val setThisConnectSuccess = { setConnectSuccess.invoke(this) }


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
        return "none"
    }


    override fun addCodec(ctx: ChannelHandlerContext) {
        val p = ctx.pipeline()
        val name = ctx.name()

        when (streamBy) {
            //when just a plain tcp connect, we just open the tcp connect and add trojan outbound handler
            Protocol.TCP -> {
                addPreHandledTcp(ctx)
                p.addBefore(name, TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
                p.addLast("AutoSuccessHandler", AutoExecHandler { setThisConnectSuccess() })
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
        val uri = URI(
            "${
                when (streamBy) {
                    Protocol.WS -> {
                        "ws"
                    }
                    Protocol.WSS -> {
                        "wss"
                    }
                    else -> {
                        throw IllegalArgumentException("unsupported stream")
                    }
                }
            }://${outboundStreamBy.wsOutboundSetting!!.host}:${outboundStreamBy.wsOutboundSetting!!.port}/${
                outboundStreamBy.wsOutboundSetting.path.removePrefix(
                    "/"
                )
            }"
        )
        logger.debug { "[${ctx.channel().id().asShortText()}] websocket handle shake, uri: $uri" }
        ctx.pipeline().addBefore(ctx.name(), "HttpClientCodec", HttpClientCodec())
        ctx.pipeline().addBefore(ctx.name(), "HttpObjectAggregator", HttpObjectAggregator(8192))
        ctx.pipeline()
            .addBefore(ctx.name(), "WebSocketClientCompressionHandler", WebSocketClientCompressionHandler.INSTANCE)
//
//        ctx.pipeline().addBefore(
//            ctx.name(), "websocket_client_handshaker", WebSocketClientProtocolHandler(
//                WebSocketClientHandshakerFactory.newHandshaker(
//                    uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
//                )
//            )
//        )

        val newPromise = ctx.channel().eventLoop().newPromise<Channel>()
        newPromise.addListener {
            if (it.isSuccess && !ctx.pipeline().names().contains(TROJAN_PROXY_OUTBOUND)) {
                ctx.pipeline().addFirst("AutoSuccessHandler", AutoExecHandler {
                    setThisConnectSuccess()
                })
                ctx.pipeline().addBefore(ctx.name(), TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
            }
        }
        ctx.pipeline().addBefore(ctx.name(), "websocket_duplex_handler", WebSocketDuplexHandler(newPromise))
        ctx.pipeline().addLast(ExceptionCaughtHandler())
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
//        ctx.pipeline().addLast(EventTriggerHandler { _, it ->
//            if (it is SslCompletionEvent) {
//                ctx.pipeline().addBefore(ctx.name(), TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
//                setThisConnectSuccess()
//                ctx.pipeline().remove(this)
//                return@EventTriggerHandler true
//            }
//            return@EventTriggerHandler false
//        })
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
        return when (streamBy) {
            Protocol.WS, Protocol.WSS -> {
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
                ctx.pipeline().addBefore(
                    "websocket_duplex_handler",
                    "WebSocketHandshakeHandler",
                    WebSocketHandshakeHandler(newHandshaker)
                )
                newHandshaker.javaClass.declaredMethods.find { it.name == "newHandshakeRequest" }!!.also {
                    it.isAccessible = true
                }.invoke(newHandshaker)
            }

            else -> null
        }
    }

    override fun handleResponse(ctx: ChannelHandlerContext, response: Any): Boolean {
        return true
    }
}

