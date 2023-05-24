package utils

import io.netty.handler.codec.socks.SocksAddressType
import java.util.regex.Pattern

object SurferUtils {

    fun getAddressType(addr: String): SocksAddressType {
        return when {
            PATTERN.matcher(addr).matches() -> SocksAddressType.IPv4
            addr.contains(":") -> SocksAddressType.IPv6
            else -> SocksAddressType.DOMAIN
        }

    }

    @Suppress("RegExpRedundantEscape")
    private val PATTERN: Pattern = Pattern.compile(
        "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$"
    )

}
