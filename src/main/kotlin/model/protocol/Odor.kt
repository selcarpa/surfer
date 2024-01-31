package model.protocol

import java.net.InetSocketAddress

data class Odor(
    val host: String,
    val port: Int,
    var desProtocol: Protocol = Protocol.TCP,
    var originProtocol: Protocol = Protocol.TCP,
    var redirectHost: String? = null,
    var redirectPort: Int? = null,
    val fromChannel: String,
    //todo takes no effect
    var notDns: Boolean = false,
    var transmissionTrans: Boolean = false,
) {
    fun socketAddress(): InetSocketAddress = InetSocketAddress(host, port)
}
