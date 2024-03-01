package inbounds

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpResponseStatus
import model.config.Inbound

class ApiHandle(private val inbound: Inbound) : SimpleChannelInboundHandler<HttpMessage>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpMessage) {
        inbound.apiSettings.filter {
           msg.headers()["auth"] != it.password
        }.first {
            return@channelRead0
        }
        ctx.channel().writeAndFlush(DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.UNAUTHORIZED))
    }

}
