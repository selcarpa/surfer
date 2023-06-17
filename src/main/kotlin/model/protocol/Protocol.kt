package model.protocol

enum class Protocol(val superProtocol: Protocol?) {
    TCP(null),
    UDP(null),
    HTTP(TCP),
    SOCKS5(TCP),
    U_KCP(UDP),
    T_KCP(TCP),
    //websocket
    WS(HTTP),
    //websocket secure
    WSS(HTTP),
    // NULL is a special protocol, it means that the protocol is not determined
    NULL(null),
    // trojan can be transmitted in any stream
    TROJAN(null),
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


}
