package protocol


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.http.DefaultHttpHeaders
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
import model.TROJAN_PROXY_OUTBOUND
import model.config.Inbound
import model.config.Outbound
import model.config.OutboundStreamBy
import model.config.TrojanSetting
import model.protocol.Odor
import model.protocol.Protocol
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.WebsocketDuplexHandler
import stream.relayAndOutbound
import utils.ChannelUtils
import utils.Sha224Utils
import utils.SurferUtils
import java.net.InetSocketAddress
import java.net.URI

class TrojanInboundHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<ByteBuf>() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private var removed = false
    override fun channelRead0(originCTX: ChannelHandlerContext, msg: ByteBuf) {
        //parse trojan package
        val trojanPackage = TrojanPackage.parse(msg)

        if (ByteBufUtil.hexDump(
                Sha224Utils.encryptAndHex(
                    SurferUtils.toUUid(inbound.trojanSetting!!.password).toString()
                ).toByteArray()
            ) == trojanPackage.hexSha224Password
        ) {
            logger.info(
                "trojan inbound: [${
                    originCTX.channel().id().asShortText()
                }], addr: ${trojanPackage.request.host}:${trojanPackage.request.port}, cmd: ${
                    Socks5CommandType.valueOf(
                        trojanPackage.request.cmd
                    )
                }"
            )
            val odor = Odor(
                host = trojanPackage.request.host,
                port = trojanPackage.request.port,
                originProtocol = Protocol.TROJAN,
                desProtocol = if (Socks5CommandType.valueOf(trojanPackage.request.cmd) == Socks5CommandType.CONNECT) {
                    Protocol.TCP
                } else {
                    Protocol.UDP
                },
                fromChannel = originCTX.channel().id().asShortText()
            )
            resolveOutbound(inbound = inbound, odor = odor).ifPresent { outbound ->
                relayAndOutbound(
                    RelayAndOutboundOp(
                        originCTX = originCTX,
                        outbound = outbound,
                        odor = odor
                    ).also { relayAndOutboundOp ->
                        relayAndOutboundOp.connectEstablishedCallback = {
                            val payload = Unpooled.buffer()
                            payload.writeBytes(ByteBufUtil.decodeHexDump(trojanPackage.payload))
                            it.writeAndFlush(payload).addListener {
                                //avoid remove this handler twice
                                if (!removed) {
                                    //Trojan protocol only need package once, then send origin data directly
                                    originCTX.pipeline().remove(this)
                                    removed = true
                                }
                            }
                        }
                        relayAndOutboundOp.connectFail = {
                            ChannelUtils.closeOnFlush(originCTX.channel())
                        }
                    }
                )
            }
        } else {
            logger.warn { "id: ${originCTX.channel().id().asShortText()}, drop trojan package, password not matched" }

        }
    }


    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is DecoderException || cause.cause is DecoderException) {
            logger.warn {
                "[${
                    ctx.channel().id().asShortText()
                }] parse trojan package failed, ${cause.message}, give a discard handler"
            }
            ctx.pipeline().forEach {
                ctx.pipeline().remove(it.value)
            }
            removed = true
            ctx.pipeline().addLast(DiscardHandler())
            return
        }
        logger.error(cause) { "[${ctx.channel().id().asShortText()}], exception caught" }

    }
}

class TrojanOutboundHandler(
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest
) : ChannelDuplexHandler() {

    private var firstPackage = true

    constructor(
        outbound: Outbound, odor: Odor
    ) : this(
        outbound.trojanSetting!!, TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(),
            odor.addressType().byteValue(),
            odor.host,
            odor.port
        )
    )

    override fun write(ctx: ChannelHandlerContext?, msg: Any?, promise: ChannelPromise?) {
        if (!firstPackage) {
            super.write(ctx, msg, promise)
            return
        }
        if (msg is ByteBuf) {
            val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
            ReferenceCountUtil.release(msg)
            val trojanByteBuf = TrojanPackage.toByteBuf(trojanPackage)
            firstPackage=false
            super.write(ctx, trojanByteBuf, promise)
            return
        }
        super.write(ctx, msg, promise)
    }


}

/**
 * convert ByteBuf to TrojanPackage
 */
fun byteBuf2TrojanPackage(msg: ByteBuf, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage {
    return TrojanPackage(
        Sha224Utils.encryptAndHex(SurferUtils.toUUid(trojanSetting.password).toString()),
        trojanRequest,
        ByteBufUtil.hexDump(msg)
    )
}

class TrojanProxy(
    private val socketAddress: InetSocketAddress,
    private val outboundStreamBy: OutboundStreamBy?,
    trojanSetting: TrojanSetting,
    trojanRequest: TrojanRequest,
    private val streamBy: Protocol,
) : ProxyHandler(socketAddress) {
    constructor(outbound: Outbound, odor: Odor, streamBy: Protocol) : this(
        InetSocketAddress(odor.redirectHost, odor.redirectPort!!),
        outbound.outboundStreamBy,
        outbound.trojanSetting!!,
        TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(),
            odor.addressType().byteValue(),
            odor.host,
            odor.port
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
            Protocol.TCP -> {
                addPreHandledTcp(ctx)
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

        p.addBefore(name, TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
        p.addLast(AutoSuccessHandler {
            val proxyHandlerClass = ProxyHandler::class.java
            proxyHandlerClass.declaredMethods.forEach {
                if (it.name == "setConnectSuccess") {
                    it.isAccessible = true
                    it.invoke(this)
                }
            }
        })

    }

    /**
     * add websocket handler before trojan outbound handler
     */
    private fun addPreHandledWs(ctx: ChannelHandlerContext) {
        val uri =
            URI(
                "ws://${outboundStreamBy!!.wsOutboundSetting!!.host}:${outboundStreamBy.wsOutboundSetting!!.port}/${
                    outboundStreamBy.wsOutboundSetting.path.removePrefix(
                        "/"
                    )
                }"
            )

        ctx.pipeline().addBefore(ctx.name(), "HttpClientCodec", HttpClientCodec())
        ctx.pipeline().addBefore(ctx.name(), "HttpObjectAggregator", HttpObjectAggregator(8192))
        ctx.pipeline().addBefore(ctx.name(), "WebSocketClientCompressionHandler", WebSocketClientCompressionHandler.INSTANCE)

        ctx.pipeline().addBefore(
            ctx.name(), "ws-handshake", WebSocketClientProtocolHandler(
                WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, true, DefaultHttpHeaders()
                )
            )
        )
        ctx.pipeline().addBefore(ctx.name(), "ws", WebsocketDuplexHandler())
    }

    /**
     * add tls handler before trojan outbound handler
     */
    private fun addPreHandledTls(ctx: ChannelHandlerContext) {
        val sslCtx: SslContext =
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
        ctx.pipeline().addBefore(
            ctx.name(),
            "ssl",
            sslCtx.newHandler(ctx.channel().alloc(), socketAddress.hostName, socketAddress.port)
        )
    }



    /**
     * tcp stream needn't any handler
     */
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
        return null
    }

    override fun handleResponse(ctx: ChannelHandlerContext, response: Any): Boolean {
        return true
    }


}

class AutoSuccessHandler(private val exec:()->Unit): ChannelInboundHandlerAdapter() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        exec()
        super.channelActive(ctx)
    }
}
