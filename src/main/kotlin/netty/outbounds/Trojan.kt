package netty.outbounds


import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.*
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.socksx.v5.Socks5CommandType
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.FutureListener
import model.config.Outbound
import model.config.TrojanSetting
import model.protocol.TrojanPackage
import model.protocol.TrojanRequest
import mu.KotlinLogging
import netty.stream.RelayInboundHandler
import netty.stream.Surfer
import utils.Sha224Utils

object Trojan {
    fun outbound(
        originCTX: ChannelHandlerContext,
        outbound: Outbound,
        destAddrType: Byte,
        destAddr: String,
        destPort: Int,
        connectSuccess: (Channel) -> ChannelFuture,
        connectFail: () -> Unit
    ) {
        val connectListener = FutureListener<Channel> { future ->
            val outboundChannel = future.now
            if (future.isSuccess) {
                connectSuccess(outboundChannel).also { channelFuture ->
                    channelFuture.addListener(ChannelFutureListener {
                        outboundChannel.pipeline().addLast(
                            TrojanOutboundHandler(), RelayInboundHandler(originCTX.channel()),
                        )
                        originCTX.pipeline().addLast(
                            TrojanRelayInboundHandler(
                                outboundChannel, outbound.trojanSetting!!, TrojanRequest(
                                    Socks5CommandType.CONNECT.byteValue(), destAddrType, destAddr, destPort
                                )
                            ),
                        )
                    })
                }
            } else {
                connectFail()
            }
        }
        Surfer.outbound(
            outbound = outbound, connectListener = connectListener, eventLoopGroup = originCTX.channel().eventLoop()
        )
    }
}

class TrojanOutboundHandler : ChannelOutboundHandlerAdapter() {
    companion object {
        private val logger = KotlinLogging.logger {}

    }

    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise?) {
        when (msg) {
            is TrojanPackage -> {
                val binaryWebSocketFrame = BinaryWebSocketFrame(
                    TrojanPackage.toByteBuf(msg)
                )
                ctx.write(binaryWebSocketFrame).addListener {
                    FutureListener<Unit> {
                        if (!it.isSuccess) {
                            logger.error(
                                "Trojan outbound write message:${msg.javaClass.name} to ${
                                    ctx.channel().id().asShortText()
                                } failed ${ctx.channel().pipeline().names()}", it.cause()
                            )
                        }
                    }
                }
            }

            else -> {
                logger.debug("TrojanOutboundHandler receive unknown message:${msg?.javaClass?.name}")
                super.write(ctx, msg, promise)
            }
        }
    }
}

class TrojanRelayInboundHandler(
    relayChannel: Channel,
    private val trojanSetting: TrojanSetting,
    private val trojanRequest: TrojanRequest,
    private val inActiveCallBack: () -> Unit = {}
) : RelayInboundHandler(relayChannel, inActiveCallBack) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is ByteBuf -> {
                val currentAllBytes = ByteArray(msg.readableBytes())
                msg.readBytes(currentAllBytes)
                ReferenceCountUtil.release(msg)
                val trojanPackage = TrojanPackage(
                    Sha224Utils.encryptAndHex(trojanSetting.password),
                    trojanRequest,
                    ByteBufUtil.hexDump(currentAllBytes)
                )
                super.channelRead(ctx, trojanPackage)
            }

            else -> {
                logger.error("TrojanRelayHandler receive unknown message:${msg.javaClass.name}")
                super.channelRead(ctx, msg)
            }
        }
    }
}
