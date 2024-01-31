package inbounds


import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import model.config.Inbound
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.relayAndOutbound
import utils.cleanHandlers
import java.net.URI


class HttpProxyServerHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<HttpRequest>(false) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.warn { "[${ctx.channel().id().asShortText()}] channel inactive" }
        super.channelInactive(ctx)
    }

    override fun channelRead0(originCTX: ChannelHandlerContext, msg: HttpRequest) {
        //http proxy and http connect method
        logger.info(
            "http inbound: [{}], method: {}, uri: {}",
            originCTX.channel().id().asShortText(),
            msg.method(),
            msg.uri()
        )
        if (msg.method() == HttpMethod.CONNECT) {
            tunnelProxy(originCTX, msg)
        } else {
            httpProxy(originCTX, msg)
        }
    }

    private fun httpProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        // If implement http capture, to code right here
        val uri = URI(request.uri())
        val port = when (uri.port) {
            -1 -> 80
            else -> uri.port
        }
        val odor = Odor(
            host = uri.host,
            port = port,
            originProtocol = Protocol.HTTP,
            desProtocol = Protocol.HTTP,
            fromChannel = originCTX.channel().id().asShortText()
        )
        val ch = EmbeddedChannel(HttpRequestEncoder())
        ch.writeOutbound(request)
        val encoded = ch.readOutbound<ByteBuf>()
        ch.close()
        val resolveOutbound = resolveOutbound(inbound, odor)

        logger.trace("http proxy outbound from {}, content: {}", originCTX.channel().id().asShortText(), request)
        resolveOutbound.ifPresent { outbound ->
            relayAndOutbound(
                RelayAndOutboundOp(
                    originCTX = originCTX,
                    outbound = outbound,
                    odor = odor
                ).also { relayAndOutboundOp ->
                    relayAndOutboundOp.connectEstablishedCallback = {
                        it.writeAndFlush(encoded).also {
                            //remove all useless listener
                            originCTX.pipeline().cleanHandlers()
                        }
                    }
                    relayAndOutboundOp.connectFail = {
                        originCTX.close()
                    }
                }
            )
        }

    }

    private fun tunnelProxy(originCTX: ChannelHandlerContext, request: HttpRequest) {
        val uri = URI(
            if (request.uri().startsWith("https://")) {
                request.uri()
            } else {
                "https://${request.uri()}"
            }
        )
        val port = when (uri.port) {
            -1 -> 443
            else -> uri.port
        }
        val odor = Odor(
            host = uri.host,
            port = port,
            originProtocol = Protocol.HTTP,
            desProtocol = Protocol.TCP,
            fromChannel = originCTX.channel().id().asShortText()
        )
        val version = request.protocolVersion()
        ReferenceCountUtil.release(request)

        val resolveOutbound = resolveOutbound(inbound, odor)
        resolveOutbound.ifPresent { outbound ->
            relayAndOutbound(
                RelayAndOutboundOp(
                    originCTX = originCTX,
                    outbound = outbound,
                    odor = odor
                ).also { relayAndOutboundOp ->
                    relayAndOutboundOp.connectEstablishedCallback = {
                        //write Connection Established
                        originCTX.writeAndFlush(
                            DefaultHttpResponse(
                                version,
                                HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                            )
                        ).also {
                            //remove all useless listener
                           originCTX.pipeline().cleanHandlers()
                        }
                    }
                    relayAndOutboundOp.connectFail = {
                        //todo: When the remote cannot be connected, the origin needs to be notified correctly
                        logger.warn { "[${originCTX.channel().id().asShortText()}] connect to remote fail" }
                        originCTX.close()
                    }
                }
            )
        }
    }
}
