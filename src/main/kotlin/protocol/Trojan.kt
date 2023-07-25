package protocol


import io.netty.contrib.handler.codec.socksx.v5.Socks5CommandType
import io.netty.contrib.handler.proxy.ProxyHandler
import io.netty5.buffer.Buffer
import io.netty5.buffer.BufferAllocator
import io.netty5.buffer.BufferUtil
import io.netty5.channel.Channel
import io.netty5.channel.ChannelHandler
import io.netty5.channel.ChannelHandlerContext
import io.netty5.channel.SimpleChannelInboundHandler
import io.netty5.handler.codec.DecoderException
import io.netty5.util.ReferenceCountUtil
import io.netty5.util.concurrent.Future
import io.netty5.util.internal.StringUtil
import model.RELAY_HANDLER_NAME
import model.TROJAN_PROXY_OUTBOUND
import model.config.Inbound
import model.config.Outbound
import model.config.TrojanSetting
import model.protocol.Odor
import model.protocol.Protocol
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.RelayInboundHandler
import stream.relayAndOutbound
import utils.ChannelUtils
import utils.Sha224Utils
import utils.SurferUtils
import java.net.InetSocketAddress

class TrojanInboundHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<Buffer>() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private var removed = false
    override fun messageReceived(originCTX: ChannelHandlerContext, msg: Buffer) {
        //parse trojan package
        val trojanPackage = TrojanPackage.parse(msg)

        if (BufferUtil.hexDump(
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
                            val payload = BufferAllocator.offHeapUnpooled().allocate(1)
                            payload.writeBytes(StringUtil.decodeHexDump(trojanPackage.payload))
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


    override fun channelExceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
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
) : ChannelHandler {

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

    override fun write(ctx: ChannelHandlerContext?, msg: Any?): Future<Void> {
        if (msg is Buffer) {
            val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
            ReferenceCountUtil.release(msg)
            val trojanByteBuf = TrojanPackage.toByteBuf(trojanPackage)
            return super.write(ctx, trojanByteBuf)
        }
        return super.write(ctx, msg)
    }
}

class TrojanRelayInboundHandler(
    private val relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest,
    inActiveCallBack: () -> Unit = {},
) : RelayInboundHandler(relayChannel, inActiveCallBack) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    constructor(
        outboundChannel: Channel, outbound: Outbound, odor: Odor
    ) : this(
        outboundChannel, outbound.trojanSetting!!, TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(),
            odor.addressType().byteValue(),
            odor.host,
            odor.port
        )
    )

    /**
     * Trojan protocol only need package once, then send origin data directly
     */

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is Buffer -> {
                val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
                ReferenceCountUtil.release(msg)
                val trojanByteBuf = TrojanPackage.toByteBuf(trojanPackage)
                super.channelRead(ctx, trojanByteBuf)

                ctx.channel().pipeline().remove(RELAY_HANDLER_NAME)
                ctx.channel().pipeline().addLast(RELAY_HANDLER_NAME, RelayInboundHandler(relayChannel))
            }

            else -> {
                logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
                super.channelRead(ctx, msg)
            }
        }
    }

}

/**
 * convert ByteBuf to TrojanPackage
 */
fun byteBuf2TrojanPackage(msg: Buffer, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage {
    return TrojanPackage(
        Sha224Utils.encryptAndHex(SurferUtils.toUUid(trojanSetting.password).toString()),
        trojanRequest,
        BufferUtil.hexDump(msg)
    )
}

class TrojanProxy(
    socketAddress: InetSocketAddress,
    trojanSetting: TrojanSetting,
    trojanRequest: TrojanRequest
) : ProxyHandler(socketAddress) {
    constructor(outbound: Outbound, odor: Odor) : this(
        InetSocketAddress(odor.redirectHost, odor.redirectPort!!),
        outbound.trojanSetting!!,
        TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(),
            odor.addressType().byteValue(),
            odor.host,
            odor.port
        )
    )

    private val trojanOutboundHandler = TrojanOutboundHandler(trojanSetting, trojanRequest)
    override fun protocol(): String {
        return "TROJAN"
    }

    override fun authScheme(): String {
        return "none"
    }

    override fun addCodec(ctx: ChannelHandlerContext) {
        val p = ctx.pipeline()
        val name = ctx.name()
        p.addBefore(name, TROJAN_PROXY_OUTBOUND, trojanOutboundHandler)
    }

    override fun removeEncoder(ctx: ChannelHandlerContext) {
        ctx.pipeline().remove(TROJAN_PROXY_OUTBOUND)
    }

    override fun removeDecoder(ctx: ChannelHandlerContext) {
        //ignored
    }

    override fun newInitialMessage(ctx: ChannelHandlerContext): Any? {
        return null
    }

    override fun handleResponse(ctx: ChannelHandlerContext, response: Any): Boolean {
        return false
    }

}
