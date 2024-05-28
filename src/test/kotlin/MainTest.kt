import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.util.CharsetUtil
import model.config.Config
import io.github.oshai.kotlinlogging.KotlinLogging
import netty.NettyServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch


class MainTest {


    companion object {
        private val logger = KotlinLogging.logger {}
        val httpServerPort: Int by lazy { choosePort() }
        private var udpEchoServerPort: Int = 0
        private val bossGroup: EventLoopGroup = NioEventLoopGroup(1)
        private val workerGroup: EventLoopGroup = NioEventLoopGroup()

        private fun choosePort(): Int {
            logger.info { "choose port" }
            val serverSocket = ServerSocket(0)
            val port = serverSocket.getLocalPort()
            serverSocket.close()
            return port
        }

        @BeforeAll
        @JvmStatic
        fun startHttpServer() {
            logger.info { "start http server" }
            val countDownLatch = CountDownLatch(1)
            Thread {
                val b = ServerBootstrap()
                b.group(bossGroup, workerGroup).channel(NioServerSocketChannel::class.java)
                    .handler(LoggingHandler(LogLevel.DEBUG)).childHandler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            val p = ch.pipeline()
                            p.addLast(HttpRequestDecoder())
                            p.addLast(HttpResponseEncoder())
                            p.addLast(HttpSnoopServerHandler())
                        }

                    });
                val ch: Channel = b.bind(httpServerPort).addListener {
                    countDownLatch.countDown()
                }.sync().channel()
                Runtime.getRuntime().addShutdownHook(Thread({
                    bossGroup.shutdownGracefully()
                    workerGroup.shutdownGracefully()
                }, "Server Shutdown Thread"))
                ch.closeFuture().sync()
            }.start()
            countDownLatch.await()
        }

        @BeforeAll
        @JvmStatic
        fun startUdpEchoServer() {
            logger.info { "start udp echo server" }
            val countDownLatch = CountDownLatch(1)
            Thread {
                Bootstrap().group(workerGroup).channel(NioDatagramChannel::class.java)
                    .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
                    .handler(object : SimpleChannelInboundHandler<io.netty.channel.socket.DatagramPacket>() {
                        override fun channelRead0(
                            ctx: ChannelHandlerContext, msg: io.netty.channel.socket.DatagramPacket
                        ) {
                            //print

                            val srcMsg = "${
                                DateTimeFormatter.ISO_DATE_TIME.format(
                                    LocalDateTime.now()
                                )
                            }, ${msg.sender()}, ${msg.content().toString(CharsetUtil.UTF_8)}"
                            logger.info("receive：$srcMsg")
                            //echo
                            ctx.writeAndFlush(
                                io.netty.channel.socket.DatagramPacket(
                                    Unpooled.copiedBuffer(srcMsg, CharsetUtil.UTF_8), msg.sender()
                                )
                            ).sync()
                        }
                    }).also {
                        it.bind(udpEchoServerPort).addListener {
                            countDownLatch.countDown()
                            udpEchoServerPort =
                                ((it as DefaultChannelPromise).channel().localAddress() as InetSocketAddress).port
                        }.sync().channel().closeFuture().await()
                    }
            }.start()
            countDownLatch.await()
        }

        @BeforeAll
        @JvmStatic
        fun startSurferServer() {
            logger.info { "start surfer server" }
            Config.ConfigurationUrl = "classpath:/MainTest.json5"
            val countDownLatch = CountDownLatch(Config.Configuration.inbounds.size)
            Thread {
                NettyServer.start(countDownLatch)
            }.start()
            countDownLatch.await()
        }

        @AfterAll
        @JvmStatic
        fun close() {
            logger.info { "close all event loop group" }
            if (!(bossGroup.isShutdown || bossGroup.isShuttingDown)) {
                bossGroup.shutdownGracefully()
            }
            if (!(workerGroup.isShutdown || workerGroup.isShuttingDown)) {
                workerGroup.shutdownGracefully()
            }
        }

    }

    @Test
    fun baseRequest() {
        httpRequest(null)
    }

    @Test
    fun viaHttpProxy() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 14272))
        httpRequest(proxy)
    }

    @Test
    fun viaSocks5Proxy() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 14270))
        httpRequest(proxy)
    }

    @Test
    fun baseUdpRequest() {
        udpRequest()
    }

    @Test
    fun udpViaSocks5Proxy() {
        udpRequest(socksHandler())
    }

    private fun udpRequest(socks5ProxyHandler: Socks5ProxyHandler? = null) {
        Bootstrap().group(workerGroup).channel(NioDatagramChannel::class.java)
            .handler(LoggingHandler(LogLevel.TRACE, ByteBufFormat.SIMPLE))
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(socks5ProxyHandler)
                    ch.pipeline()
                        .addLast(object : SimpleChannelInboundHandler<DatagramPacket>() {
                            override fun channelRead0(
                                ctx: ChannelHandlerContext, msg: DatagramPacket
                            ) {
                                //print

                                val srcMsg = "${
                                    DateTimeFormatter.ISO_DATE_TIME.format(
                                        LocalDateTime.now()
                                    )
                                }, ${msg.sender()}, ${msg.content().toString(CharsetUtil.UTF_8)}"
                                logger.info("receive：$srcMsg")
                            }
                        })
                }

            }).also {
                it.connect(InetSocketAddress("127.0.0.1", udpEchoServerPort)).addListener {
                }.sync().channel().closeFuture().await()
            }
    }

    private fun socksHandler(): Socks5ProxyHandler = Socks5ProxyHandler(InetSocketAddress("127.0.0.1", 14270))

    private fun httpRequest(proxy: Proxy?) {
        val httpClient = if (proxy != null) {
            HttpClient.newBuilder().proxy(object : ProxySelector() {
                override fun select(uri: URI?): MutableList<Proxy> {
                    return mutableListOf(proxy)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    throw ioe!!
                }
            }).build()
        } else {
            HttpClient.newBuilder().build()
        }
        val url = "http://127.0.0.1:${httpServerPort}"

        val request: java.net.http.HttpRequest? =
            java.net.http.HttpRequest.newBuilder().uri(URI.create(url)).GET().build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val statusCode: Int = response.statusCode()
            val responseBody: String = response.body()
            logger.info("Status Code: $statusCode")
            logger.info("Response Body: $responseBody")
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

}
