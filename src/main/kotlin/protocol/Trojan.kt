package protocol


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.handler.proxy.ProxyHandler
import io.netty.util.ReferenceCountUtil
import model.RELAY_HANDLER_NAME
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
                }], addr: ${trojanPackage.request.host}:${trojanPackage.request.port}"
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
            is ByteBuf -> {
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
fun byteBuf2TrojanPackage(msg: ByteBuf, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage {
    return TrojanPackage(
        Sha224Utils.encryptAndHex(SurferUtils.toUUid(trojanSetting.password).toString()),
        trojanRequest,
        ByteBufUtil.hexDump(msg)
    )
}

class TrojanProxy(socketAddress: InetSocketAddress) : ProxyHandler(socketAddress) {
    override fun protocol(): String {
        return "TROJAN"
    }

    override fun authScheme(): String {
        return "TROJAN"
    }

    override fun addCodec(ctx: ChannelHandlerContext) {
        val p = ctx.pipeline()
        val name = ctx.name()
    }

    override fun removeEncoder(ctx: ChannelHandlerContext) {
        TODO("Not yet implemented")
    }

    override fun removeDecoder(ctx: ChannelHandlerContext) {
        TODO("Not yet implemented")
    }

    override fun newInitialMessage(ctx: ChannelHandlerContext): Any {
        TODO("Not yet implemented")
    }

    override fun handleResponse(ctx: ChannelHandlerContext, response: Any): Boolean {
        TODO("Not yet implemented")
    }

}
