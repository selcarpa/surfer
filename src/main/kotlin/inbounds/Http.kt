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
import io.github.oshai.kotlinlogging.KotlinLogging
import rule.resolveOutbound
import stream.RelayAndOutboundOp
import stream.relayAndOutbound
import utils.cleanHandlers
import java.net.URI

private val logger = KotlinLogging.logger {}

class HttpProxyServerHandler(private val inbound: Inbound) : SimpleChannelInboundHandler<HttpRequest>(false) {

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        ctx.channel().config().setAutoRead(false)
        ctx.read()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        logger.warn { "[${ctx.channel().id().asShortText()}] channel inactive" }
        super.channelInactive(ctx)
    }

    override fun channelRead0(originCTX: ChannelHandlerContext, request: HttpRequest) {
        //http proxy and http connect method
        logger.info {
            "[${
                originCTX.channel().id().asShortText()
            }] http inbounded, method: ${request.method()}, uri: ${request.uri()}"
        }
        if (request.method() == HttpMethod.CONNECT) {
            tunnelProxy(originCTX, request)
        } else {
            httpProxy(originCTX, request)
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
        val resolveOutbound = resolveOutbound(inbound.tag, odor)

        //This code actually prints out the client's request, in this case the request will be monitored. It is not safe to use proxy for http when you not trust in proxy provider
        logger.trace {
            "[${
                originCTX.channel().id().asShortText()
            }] http proxy inbounded, host: [${request}]"
        }

        resolveOutbound.ifPresent { outbound ->
            relayAndOutbound(RelayAndOutboundOp(
                originCTX = originCTX, outbound = outbound, odor = odor
            ).also { relayAndOutboundOp ->
                relayAndOutboundOp.connectEstablishedCallback = { proxyChannel, f ->
                    proxyChannel.writeAndFlush(encoded).also {
                        //remove all useless listener
                        originCTX.pipeline().cleanHandlers()
                        originCTX.channel().config().setAutoRead(true)
                        f()
                    }
                }
                relayAndOutboundOp.connectFail = {
                    originCTX.close()
                }
            })
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

        val resolveOutbound = resolveOutbound(inbound.tag, odor)
        resolveOutbound.ifPresent { outbound ->
            relayAndOutbound(RelayAndOutboundOp(
                originCTX = originCTX, outbound = outbound, odor = odor
            ).also { relayAndOutboundOp ->
                relayAndOutboundOp.connectEstablishedCallback = { proxyChannel, f ->
                    //write Connection Established
                    originCTX.writeAndFlush(
                        DefaultHttpResponse(
                            version,
                            HttpResponseStatus(HttpResponseStatus.OK.code(), "Connection established"),
                        ),
                        originCTX.newPromise().addListener {
                            f()
                        }
                    ).also {
                        originCTX.pipeline().cleanHandlers()
                        //remove all useless listener
                        originCTX.channel().config().setAutoRead(true)
                    }
                }
                relayAndOutboundOp.connectFail = {
                    //todo: When the remote cannot be connected, the origin needs to be notified correctly
                    logger.warn { "[${originCTX.channel().id().asShortText()}] connect to remote fail" }
                    originCTX.close()
                }
            })
        }
    }
}
