package model.protocol

data class TrojanRequest(val cmd: String, val atyp: Int, val host: String, val port: Int)
