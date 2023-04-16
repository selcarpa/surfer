package netty

import io.klogging.NoCoLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.socksx.v5.*
import io.netty.util.ReferenceCountUtil

class Socks5CommandRequestInboundHandler(private val clientWorkGroup: EventLoopGroup) : NoCoLogging,
    SimpleChannelInboundHandler<DefaultSocks5CommandRequest>() {
    @Throws(Exception::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: DefaultSocks5CommandRequest) {
        val socks5AddressType: Socks5AddressType = msg.dstAddrType()
        if (msg.type() != Socks5CommandType.CONNECT) {
            logger.debug("receive commandRequest type=${msg.type()}")
            ReferenceCountUtil.retain(msg)
            ctx.fireChannelRead(msg)
            return
        }
        logger.debug("connect to server，ip=${msg.dstAddr()},port=${msg.dstPort()}")
        val bootstrap = Bootstrap()
        bootstrap.group(clientWorkGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx1: ChannelHandlerContext, msg: Any) {
                    ctx.writeAndFlush(msg)
                }
            })
        val future: ChannelFuture = bootstrap.connect(msg.dstAddr(), msg.dstPort())
        future.addListener(object : ChannelFutureListener {
            @Throws(Exception::class)
            override fun operationComplete(future: ChannelFuture) {
                future.addListener(ChannelFutureListener { future1 ->
                    if (future1.isSuccess) {
                        ctx.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                future1.channel().writeAndFlush(msg)
                            }
                        })
                        val commandResponse =
                            DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, socks5AddressType)
                        ctx.writeAndFlush(commandResponse)
                        ctx.pipeline().remove(Socks5CommandRequestInboundHandler::class.java)
                        ctx.pipeline().remove(Socks5CommandRequestDecoder::class.java)
                    } else {
                        logger.error("connect failure,address=${msg.dstAddr()},port=${msg.dstPort()}")
                        val commandResponse =
                            DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, socks5AddressType)
                        ctx.writeAndFlush(commandResponse)
                        future1.channel().close()
                    }
                })
            }
        })
    }
}
