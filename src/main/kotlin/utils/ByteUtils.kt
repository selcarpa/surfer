package utils


object ByteUtils {
    /**
     * 去除前面的0
     *
     * @param bytes
     */
    fun trimZero(bytes: ByteArray, digitsAtLeast: Int): ByteArray {
        var i = 0
        while (i < bytes.size && bytes[i].toInt() == 0) {
            i++
        }
        if (bytes.size - i < digitsAtLeast) {
            i = bytes.size - digitsAtLeast
        }
        val newBytes = ByteArray(bytes.size - i)
        System.arraycopy(bytes, i, newBytes, 0, newBytes.size)
        return newBytes
    }
}
