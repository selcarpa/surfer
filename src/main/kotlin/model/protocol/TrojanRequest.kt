package model.protocol

data class TrojanRequest(val cmd: Byte, val atyp: Byte, val host: String, val port: Int)

data class TrojanPackage(val hexSha224Password: String, val request: TrojanRequest, val payload: String)
