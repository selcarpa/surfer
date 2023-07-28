package model.protocol

enum class Protocol(private val superProtocol: Protocol?) {
    TCP(null),
    UDP(null),
    TLS(TCP),
    HTTP(TCP),
    SOCKS5(TCP),
    UKCP(null),
    //websocket
    WS(HTTP),

    //websocket secure
    WSS(HTTP),

    // NULL is a special protocol, it means that the protocol is not determined
    NULL(null),

    // trojan can be transmitted in any stream
    TROJAN(null),

    //galaxy is an outbound protocol
    GALAXY(null)
    ;

    companion object {
        fun valueOfOrNull(name: String): Protocol {
            return try {
                valueOf(name.uppercase())
            } catch (e: Exception) {
                NULL
            }
        }
    }

    fun topProtocol(): Protocol {
        var protocol = this
        while (protocol.superProtocol != null) {
            protocol = protocol.superProtocol!!
        }
        return protocol
    }


}
