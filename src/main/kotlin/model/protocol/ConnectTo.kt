package model.protocol

import utils.SurferUtils

data class ConnectTo(val address: String, val port: Int) {
    fun addressType() = SurferUtils.getAddressType(address)
}
