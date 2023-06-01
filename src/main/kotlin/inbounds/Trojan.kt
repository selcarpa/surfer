package inbounds

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import model.config.Inbound
import model.protocol.ConnectTo
import model.protocol.TrojanPackage
import mu.KotlinLogging
import outbounds.TrojanRelayInboundHandler
import route.Route
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
            logger.debug { "id: ${originCTX.channel().id().asShortText()}, accept trojan inbound" }
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
                                logger.debug {
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
                            val payload = Unpooled.buffer()
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
