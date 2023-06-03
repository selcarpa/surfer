package protocol


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.util.ReferenceCountUtil
import model.config.Inbound
import model.config.Outbound
import model.config.TrojanSetting
import model.protocol.ConnectTo
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import route.Route
import stream.RelayInboundHandler
import stream.Surfer
import utils.ChannelUtils
import utils.Sha224Utils

class TrojanInboundHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<ByteBuf>() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    override fun channelRead0(originCTX: ChannelHandlerContext, msg: ByteBuf) {
        //parse trojan package
        val trojanPackage = TrojanPackage.parse(msg)

        val trojanSetting = inbound.trojanSettings!!.stream().filter {
            ByteBufUtil.hexDump(Sha224Utils.encryptAndHex(it.password).toByteArray()) == trojanPackage.hexSha224Password
        }.findFirst()
        if (trojanSetting.isPresent) {
            logger.info(
                "trojan inbound: [${
                    originCTX.channel().id().asShortText()
                }], addr: ${trojanPackage.request.host}:${trojanPackage.request.port}"
            )
            Route.resolveOutbound(inbound).ifPresent { outbound ->
                val connectTo = ConnectTo(trojanPackage.request.host, trojanPackage.request.port)
                when (outbound.protocol) {
                    "galaxy" -> {
                        Surfer.relayAndOutbound(
                            originCTX = originCTX,
                            outbound = outbound,
                            connectEstablishedCallback = {
                                val payload = Unpooled.buffer()
                                payload.writeBytes(ByteBufUtil.decodeHexDump(trojanPackage.payload))
                                logger.trace {
                                    "id: ${
                                        originCTX.channel().id().asShortText()
                                    }, write trojan package to galaxy, payload: $payload"
                                }
                                it.writeAndFlush(payload).addListener {
                                    //Trojan protocol only need package once, then send origin data directly
                                    originCTX.pipeline().remove(this)
                                }
                            },
                            connectFail = {
                                //while connect failed, write failure response to client, and close the connection
                                ChannelUtils.closeOnFlush(originCTX.channel())
                            },
                            connectTo = connectTo
                        )

                    }

                    "trojan" -> {
                        Surfer.relayAndOutbound(originCTX, {
                            TrojanRelayInboundHandler(
                                it,
                                outbound,
                                ConnectTo(trojanPackage.request.host, trojanPackage.request.port),
                                firstPackage = true
                            )
                        }, outbound, {
                            val payload = ReferenceCountUtil.releaseLater(Unpooled.buffer())
                            payload.writeBytes(ByteBufUtil.decodeHexDump(trojanPackage.payload))
                            it.writeAndFlush(payload).addListener {
                                //Trojan protocol only need package once, then send origin data directly
                                originCTX.pipeline().remove(this)
                            }
                        }, {
                            //ignored
                        }, {
                            originCTX.close()
                        }, connectTo)

                    }

                    else -> {
                        logger.error(
                            "id: ${
                                originCTX.channel().id().asShortText()
                            }, protocol=${outbound.protocol} not support"
                        )
                    }
                }
            }
        } else {
            logger.warn { "id: ${originCTX.channel().id().asShortText()}, drop trojan package, no password matched" }

        }
    }


    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        logger.error(cause) { "id: ${ctx!!.channel().id().asShortText()}, exception caught" }
    }
}

class TrojanRelayInboundHandler(
    relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest,
    inActiveCallBack: () -> Unit = {},
    private var firstPackage: Boolean = true
) : RelayInboundHandler(relayChannel, inActiveCallBack) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    constructor(
        outboundChannel: Channel, outbound: Outbound, connectTo: ConnectTo, firstPackage: Boolean = false
    ) : this(
        outboundChannel, outbound.trojanSetting!!, TrojanRequest(
            Socks5CommandType.CONNECT.byteValue(),
            connectTo.addressType().byteValue(),
            connectTo.address,
            connectTo.port
        ), firstPackage = firstPackage
    )

    /**
     * Trojan protocol only need package once, then send origin data directly
     */

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (firstPackage) {
            when (msg) {
                is ByteBuf -> {
                    val trojanPackage = byteBuf2TrojanPackage(msg, trojanSetting, trojanRequest)
                    ReferenceCountUtil.release(msg)
                    val trojanByteBuf = TrojanPackage.toByteBuf(trojanPackage)
                    super.channelRead(ctx, trojanByteBuf)
                    firstPackage = false
                    ReferenceCountUtil.release(trojanByteBuf)
                }

                else -> {
                    logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
                    super.channelRead(ctx, msg)
                }
            }
        } else {
            super.channelRead(ctx, msg)
        }
    }

}

fun byteBuf2TrojanPackage(msg: ByteBuf, trojanSetting: TrojanSetting, trojanRequest: TrojanRequest): TrojanPackage {
    return TrojanPackage(
        Sha224Utils.encryptAndHex(trojanSetting.password), trojanRequest, ByteBufUtil.hexDump(msg)
    )
}
