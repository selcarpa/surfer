package utils

import io.netty5.buffer.BufferUtil
import java.security.MessageDigest

object Sha224Utils {
    fun encryptAndHex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-224")
        val hash = digest.digest(input.toByteArray())
        return BufferUtil.hexDump(hash)
    }
}
