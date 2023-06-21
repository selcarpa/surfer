package utils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*

/**
 * copy and rewrite by https://stackoverflow.com/questions/40230276/how-to-make-a-type-5-uuid-in-java
 */
object UUIDv5 {
    private val UTF8 = StandardCharsets.UTF_8
    fun uuidOf(name: String) = uuidOf(name, null as ByteArray?)

    fun uuidOf(name: String, namespace: UUID?): UUID = uuidOf(name.toByteArray(UTF8), namespace)
    fun uuidOf(name: String, namespace: ByteArray?): UUID = uuidOf(name.toByteArray(UTF8), namespace)

    fun uuidOf(name: ByteArray, namespace: UUID?): UUID = uuidOf(name, namespace?.let { toBytes(namespace) })
    fun uuidOf(name: ByteArray, namespace: ByteArray?): UUID {
        val md: MessageDigest = try {
            MessageDigest.getInstance("SHA-1")
        } catch (e: NoSuchAlgorithmException) {
            throw InternalError("SHA-1 not supported")
        }
        namespace?.let { md.update(it) }
        md.update(name)
        val sha1Bytes = md.digest()
        sha1Bytes[6] = (sha1Bytes[6].toInt() and 0x0f).toByte() /* clear version        */
        sha1Bytes[6] = (sha1Bytes[6].toInt() or 0x50).toByte() /* set to version 5     */
        sha1Bytes[8] = (sha1Bytes[8].toInt() and 0x3f).toByte() /* clear variant        */
        sha1Bytes[8] = (sha1Bytes[8].toInt() or 0x80).toByte() /* set to IETF variant  */
        return fromBytes(sha1Bytes)
    }

    private fun fromBytes(data: ByteArray): UUID {
        // Based on the private UUID(bytes[]) constructor
        var msb: Long = 0
        var lsb: Long = 0
        assert(data.size >= 16)
        for (i in 0..7) msb = msb shl 8 or (data[i].toInt() and 0xff).toLong()
        for (i in 8..15) lsb = lsb shl 8 or (data[i].toInt() and 0xff).toLong()
        return UUID(msb, lsb)
    }

    private fun toBytes(uuid: UUID): ByteArray {
        // inverted logic of fromBytes()
        val out = ByteArray(16)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0..7) out[i] = (msb shr (7 - i) * 8 and 0xffL).toByte()
        for (i in 8..15) out[i] = (lsb shr (15 - i) * 8 and 0xffL).toByte()
        return out
    }
}
