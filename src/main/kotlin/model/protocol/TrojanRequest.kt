package model.protocol

import io.netty.handler.codec.socksx.v5.Socks5AddressType
import io.netty.handler.codec.socksx.v5.Socks5CommandType

data class TrojanRequest(val cmd: Socks5CommandType, val atyp: Socks5AddressType, val host: String, val port: Int)

data class TrojanPackage(val hexSha224Password: String, val request: TrojanRequest, val payload: String)
