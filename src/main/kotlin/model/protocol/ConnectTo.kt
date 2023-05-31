package model.protocol

import utils.SurferUtils
import java.net.InetSocketAddress

data class ConnectTo(val address: String, val port: Int) {
    fun socketAddress(): InetSocketAddress = InetSocketAddress(address, port)

    fun addressType() = SurferUtils.getAddressType(address)
}
