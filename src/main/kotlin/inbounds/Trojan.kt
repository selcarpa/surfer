package inbounds

import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import model.config.Inbound
import model.protocol.Odor
import model.protocol.Protocol
import model.protocol.TrojanPackage
import protocol.DiscardHandler
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.relayAndOutbound
import utils.closeOnFlush
import utils.toSha224

private val logger = KotlinLogging.logger { }

class TrojanInboundHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<ByteBuf>() {

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.channel().config().setAutoRead(false)
        ctx.read()
    }

    private var handshaked = false

    override fun channelRead0(originCTX: ChannelHandlerContext, msg: ByteBuf) {
        if (!handshaked) {
            //parse trojan package
            val trojanPackage = TrojanPackage.parse(msg)

            inbound.trojanSettings.filter {
                ByteBufUtil.hexDump(
                    it.password.toSha224().toByteArray()
                ) == trojanPackage.hexSha224Password
            }.firstOrNull { trojanSetting ->
                logger.info {
                    "trojan inbound: [${
                        originCTX.channel().id().asShortText()
                    }], addr: ${trojanPackage.request.host}:${trojanPackage.request.port}, cmd: ${
                        Socks5CommandType.valueOf(
                            trojanPackage.request.cmd
                        )
                    }"
                }
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
                logger.debug {
                    "trojan inbound: [${
                        originCTX.channel().id().asShortText()
                    }], odor: $odor"
                }
                resolveOutbound(trojanSetting.tag ?: inbound.tag, odor).ifPresent { outbound ->
                    relayAndOutbound(
                        RelayAndOutboundOp(
                            originCTX = originCTX, outbound = outbound, odor = odor
                        ).also { relayAndOutboundOp ->
                            relayAndOutboundOp.connectEstablishedCallback = { proxyChannel, f ->
                                val payload = Unpooled.buffer()
                                payload.writeBytes(ByteBufUtil.decodeHexDump(trojanPackage.payload))
                                originCTX.pipeline().remove(this@TrojanInboundHandler)
                                handshaked = true
                                proxyChannel.writeAndFlush(payload).addListener {
                                    originCTX.channel().config().setAutoRead(true)
                                    f()
                                }
                            }
                            relayAndOutboundOp.connectFail = {
                                originCTX.channel().closeOnFlush()
                            }
                        })
                }
                return@channelRead0
            }
        } else {
            originCTX.fireChannelRead(msg)
        }
        logger.warn { "${originCTX.channel().id().asShortText()}, drop trojan package, password not matched" }
        originCTX.close()
    }


    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is DecoderException || cause.cause is DecoderException) {
            logger.warn(cause) {
                "[${
                    ctx.channel().id().asShortText()
                }] parse trojan package failed, ${cause.message}, give a discard handler"
            }
            ctx.pipeline().forEach {
                ctx.pipeline().remove(it.value)
            }
            ctx.pipeline().addLast(DiscardHandler())
            return
        }
        logger.error(cause) { "[${ctx.channel().id().asShortText()}], exception caught" }

    }
}
