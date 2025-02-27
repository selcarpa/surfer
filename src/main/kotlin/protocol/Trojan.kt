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
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.ReferenceCountUtil
import model.DEFAULT_EXCEPTION_CAUGHT_HANDLER_NAME
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
import stream.*
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
        trojanSetting.password.toSha224(), trojanRequest, ByteBufUtil.hexDump(msg)
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
        const val EMBEDDED_RELAY_INBOUND_HANDLER_NAME = "${RELAY_HANDLER_NAME}:embedded-inbound"
        const val EMBEDDED_RELAY_OUTBOUND_HANDLER_NAME = "${RELAY_HANDLER_NAME}:embedded-outbound"
        const val TO_EMBEDDED_RELAY_INBOUND_HANDLER_NAME = "${RELAY_HANDLER_NAME}:2-embedded-inbound"
        const val TO_EMBEDDED_RELAY_OUTBOUND_HANDLER_NAME = "${RELAY_HANDLER_NAME}:2-embedded-outbound"

    }

    private val setThisConnectSuccess = {
        setConnectSuccess.invoke(this)
    }

    private var doFirstSend: () -> Unit = {}

    private val embeddedChannel = EmbeddedChannel()

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


    override fun addCodec(originCTX: ChannelHandlerContext) {
        addEmbeddedChannelRelay(originCTX)
        when (streamBy) {
            //when just a plain tcp connect, we just open the tcp connect and add trojan outbound handler
            Protocol.TCP -> {
                addPreHandledTcp(originCTX)
            }

            Protocol.TLS -> {
                addPreHandledTls(originCTX)
                addPreHandledTcp(originCTX)
            }

            Protocol.WS -> {
                addPreHandledWs(originCTX)
            }

            Protocol.WSS -> {
                addPreHandledTls(originCTX)
                addPreHandledWs(originCTX)
            }

            else -> throw IllegalArgumentException("unsupported stream")
        }

        embeddedChannel.pipeline().addLast(EMBEDDED_RELAY_INBOUND_HANDLER_NAME, RelayInBound2CTXHandler(originCTX))
        embeddedChannel.pipeline().addLast(DEFAULT_EXCEPTION_CAUGHT_HANDLER_NAME, ExceptionCaughtHandler())
    }

    private fun addEmbeddedChannelRelay(originCTX: ChannelHandlerContext) {
        embeddedChannel.pipeline().addFirst(EMBEDDED_RELAY_OUTBOUND_HANDLER_NAME, RelayOutBound2CTXHandler(originCTX))

        originCTX.pipeline().addBefore(
            originCTX.name(),
            TO_EMBEDDED_RELAY_INBOUND_HANDLER_NAME,
            RelayInBound2EmbeddedChannelHandler(embeddedChannel)
        )
        originCTX.pipeline().addAfter(
            originCTX.name(),
            TO_EMBEDDED_RELAY_OUTBOUND_HANDLER_NAME,
            RelayOutBound2EmbeddedChannelHandler(embeddedChannel)
        )
        originCTX.pipeline().addLast(DEFAULT_EXCEPTION_CAUGHT_HANDLER_NAME, ExceptionCaughtHandler())
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
        newPromise.addListener {
            if (it.isSuccess) {
                embeddedChannel.pipeline().addLast(TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
                setThisConnectSuccess()
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
                    } else {
                        ctx.fireChannelRead(msg)
                    }
                }
            })
        embeddedChannel.pipeline().addLast("websocket_duplex_handler", WebSocketDuplexHandler(newPromise))


        doFirstSend = {
            newHandshaker.handshake(embeddedChannel)
        }

    }

    /**
     * add tls handler before trojan outbound handler
     */
    private fun addPreHandledTls(originCTX: ChannelHandlerContext) {
        val sslCtx: SslContext =
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
        originCTX.pipeline().addFirst(
            "ssl", sslCtx.newHandler(originCTX.channel().alloc(), socketAddress.hostName, socketAddress.port)
        )
    }


    /**
     * tcp stream needn't any handler
     */
    private fun addPreHandledTcp(ctx: ChannelHandlerContext) {
        embeddedChannel.pipeline().addLast(TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
        ctx.pipeline().addLast("AutoSuccessHandler", ActiveAutoExecHandler { setThisConnectSuccess() })
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
