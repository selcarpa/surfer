package inbounds

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpResponseStatus
import model.config.Inbound

class ApiHandle(private val inbound: Inbound) : SimpleChannelInboundHandler<HttpMessage>() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpMessage) {
        if (inbound.apiSetting!!.password == "" || msg.headers()["auth"] != inbound.apiSetting.password) {
            ctx.channel().writeAndFlush(DefaultHttpResponse(msg.protocolVersion(), HttpResponseStatus.UNAUTHORIZED))
        }


    }

}
