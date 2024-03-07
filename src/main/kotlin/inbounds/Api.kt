package inbounds

import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import kotlinx.serialization.encodeToString
import model.config.Config.Configuration
import model.config.Inbound
import model.config.json
import model.protocol.Protocol
import mu.KotlinLogging
import netty.NettyServer
import netty.NettyServer.portInboundBinds

private val logger = KotlinLogging.logger {}

class ApiHandle(private val inbound: Inbound) : SimpleChannelInboundHandler<FullHttpRequest>() {
    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {

        if (inbound.apiSettings.isEmpty()) {
            val doCall = endpoints[request.uri()]
            if (doCall != null) {
                doCall(request, ctx.channel())
            } else {
                ctx.channel()
                    .writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.NOT_FOUND))
                ctx.close()
            }
            return
        }

        inbound.apiSettings.filter {
            request.headers()["auth"] != it.password
        }.first {
            return@channelRead0
        }
        ctx.channel().writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.UNAUTHORIZED))
    }

}


val endpoints = mapOf(
    "/add/inbound" to ::addInbound,
    "/remove/inbound/by/id" to ::removeInboundById,
    "/export/configuration" to ::downloadConfiguration,
)

fun addInbound(request: FullHttpRequest, channel: Channel) {
    val inbound = json.decodeFromString<Inbound>(request.content().toString(Charsets.UTF_8))
    Configuration.inbounds.add(inbound)

    when (Protocol.valueOfOrNull(inbound.protocol).topProtocol()) {
        Protocol.TCP -> {
            NettyServer.tcpBind(inbound)
            channel.writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.OK))
            channel.close()
        }

        Protocol.UKCP -> {
            NettyServer.ukcpBind(inbound)
            channel.writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.OK))
            channel.close()
        }

        else -> {
            logger.error { "unsupported top protocol ${Protocol.valueOfOrNull(inbound.protocol).topProtocol()}" }
        }
    }

}

fun downloadConfiguration(request: FullHttpRequest, channel: Channel) {
    val configuration = json.encodeToString(Configuration)

    val response = DefaultFullHttpResponse(
        request.protocolVersion(),
        HttpResponseStatus.OK,
        ReferenceCountUtil.releaseLater(Unpooled.wrappedBuffer(configuration.toByteArray()))
    )
    response.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/json")
    channel.writeAndFlush(response)
    channel.close()
}

fun removeInboundById(request: FullHttpRequest, channel: Channel) {
    val id = request.content().toString(Charsets.UTF_8)
    Configuration.inbounds.removeIf { it.id == id }
    val portInboundBind = portInboundBinds.first {
        it.second == id
    }
    when (Protocol.valueOfOrNull(portInboundBind.third.protocol).topProtocol()) {
        Protocol.TCP -> {
            channel.writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.OK))
            channel.close()
        }

        Protocol.UKCP -> {
            channel.writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.OK))
            channel.close()
        }

        else -> {
            logger.error {
                "unsupported top protocol ${
                    Protocol.valueOfOrNull(portInboundBind.third.protocol).topProtocol()
                }"
            }
        }
    }

    channel.writeAndFlush(DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.NOT_FOUND))
    channel.close()
}
