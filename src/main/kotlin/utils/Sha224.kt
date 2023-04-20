package utils

import io.netty.buffer.ByteBufUtil
import java.security.MessageDigest

object Sha224 {
    fun encryptAndHex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-224")
        val hash = digest.digest(input.toByteArray())
        return ByteBufUtil.hexDump(hash)
    }
}
