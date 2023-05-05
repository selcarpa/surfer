
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse
import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus
import model.config.Inbound
import model.protocol.TrojanPackage
import mu.KotlinLogging
import netty.outbounds.GalaxyOutbound
import utils.ChannelUtils
import utils.EasyPUtils
import utils.Sha224Utils

class TrojanInboundHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<ByteBuf>() {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    override fun channelRead0(originCTX: ChannelHandlerContext, msg: ByteBuf) {
        //parse trojan package
        val trojanPackage = TrojanPackage.parse(msg)

        inbound.trojanSettings!!.stream()
            .filter { Sha224Utils.encryptAndHex(it.password) == trojanPackage.hexSha224Password }.findFirst()
            .ifPresent {

                EasyPUtils.resolveOutbound(inbound).ifPresent { outbound ->
                    when (outbound.protocol) {
                        "galaxy" -> {
                            GalaxyOutbound.outbound(
                                originCTX,
                                outbound,
                                trojanPackage.request.host,
                                trojanPackage.request.port,
                                {
                                    originCTX.newPromise().setSuccess()
                                },
                                {
                                    //while connect failed, write failure response to client, and close the connection
                                    originCTX.channel().writeAndFlush(
                                        DefaultSocks5CommandResponse(
                                            Socks5CommandStatus.FAILURE,
                                            Socks5AddressType.valueOf(trojanPackage.request.atyp)
                                        )
                                    )
                                    ChannelUtils.closeOnFlush(originCTX.channel())
                                })
                        }

                        "trojan" -> {
                            TODO("Not yet implemented")
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


            }
    }


    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        logger.error(cause) { "id: ${ctx!!.channel().id().asShortText()}, exception caught" }
    }
}
