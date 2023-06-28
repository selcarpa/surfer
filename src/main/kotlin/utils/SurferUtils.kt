package utils

import io.netty.handler.codec.socks.SocksAddressType
import io.netty.util.NetUtil
import mu.KotlinLogging
import java.util.*

object SurferUtils {
    private val logger = KotlinLogging.logger {}

    fun getAddressType(addr: String): SocksAddressType {
        return when {
            NetUtil.isValidIpV4Address(addr) -> SocksAddressType.IPv4
            NetUtil.isValidIpV6Address(addr) -> SocksAddressType.IPv6
            else -> SocksAddressType.DOMAIN
        }

    }
    /**
     * any string to uuid, when it is an actual uuid, return it, otherwise, return an uuid generated by uuidv5
     */
    fun toUUid(plainString:String): UUID {
        return try {
            UUID.fromString(plainString)
        } catch (e: Exception) {
            logger.trace { "to UUid failed, ${e.message}" }
            UUIDv5.uuidOf(plainString)
        }
    }

}
