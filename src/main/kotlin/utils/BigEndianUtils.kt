package utils

import java.nio.ByteBuffer

/**
 * 大端工具类
 */
object BigEndianUtils {
    /**
     * 将int转化为byte数组
     *
     * @param srcInt 源整数
     * @return byte数组
     */
    fun int2ByteArray(srcInt: Int): ByteArray {
        val allocate = ByteBuffer.allocate(4)
        return allocate.putInt(srcInt).array()
    }

    /**
     * 将int转化为byte数组，并去除前缀0
     *
     * @param srcInt 源整数
     * @return byte数组
     */
    fun int2ByteArrayTrimZero(srcInt: Int, digitsAtLeast: Int): ByteArray {
        return ByteUtils.trimZero(int2ByteArray(srcInt), digitsAtLeast)
    }

    /**
     * 将byte数组转化为int
     *
     * @param srcBytes 源数组
     * @return int整数
     */
    fun bytes2Int(srcBytes: ByteArray): Int {
        var result = 0
        for (i in srcBytes.indices) {
            result = (result shl 8) + (srcBytes[i].toInt() and 0xFF)
        }
        return result
    }
}
