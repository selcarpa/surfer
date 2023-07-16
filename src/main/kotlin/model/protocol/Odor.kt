package model.protocol

import utils.SurferUtils
import java.net.InetSocketAddress

data class Odor(
    val host: String,
    val port: Int,
    var desProtocol: Protocol = Protocol.TCP,
    var originProtocol: Protocol = Protocol.TCP,
    var redirectHost: String? = null,
    var redirectPort: Int? = null,
    val fromChannel: String,
    var notDns: Boolean = false,
    var transmissionTrans: Boolean = false,
) {
    fun socketAddress(): InetSocketAddress = InetSocketAddress(
        if (redirectHost != null) {
            redirectHost
        } else {
            host
        },
        if (redirectPort != null) {
            redirectPort!!
        } else {
            port
        }

    )


    fun addressType() = SurferUtils.getAddressType(
        if (redirectHost != null) {
            redirectHost!!
        } else {
            host
        }
    )
}
