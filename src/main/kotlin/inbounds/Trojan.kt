
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import model.config.Inbound
import model.protocol.TrojanPackage
import mu.KotlinLogging
import outbounds.GalaxyOutbound
import outbounds.Trojan
import route.Route
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
                when (outbound.protocol) {
                    "galaxy" -> {
                        GalaxyOutbound.outbound(originCTX, outbound, trojanPackage.request.host, trojanPackage.request.port, {
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
                        }, {
                            //while connect failed, write failure response to client, and close the connection
                            ChannelUtils.closeOnFlush(originCTX.channel())
                        })
                    }

                    "trojan" -> {
                        Trojan.outbound(originCTX, outbound, trojanPackage.request.atyp, trojanPackage.request.host, trojanPackage.request.port, {
                            val payload = Unpooled.buffer()
                            payload.writeBytes(ByteBufUtil.decodeHexDump(trojanPackage.payload))
                            it.writeAndFlush(payload).addListener {
                                //Trojan protocol only need package once, then send origin data directly
                                originCTX.pipeline().remove(this)
                            }
                        }, {
                            //while connect failed, write failure response to client, and close the connection
                            ChannelUtils.closeOnFlush(originCTX.channel())
                        })
                    }

                    else -> {
                        logger.error("id: ${
                            originCTX.channel().id().asShortText()
                        }, protocol=${outbound.protocol} not support")
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
