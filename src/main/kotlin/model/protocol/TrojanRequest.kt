package model.protocol

import io.netty.contrib.handler.codec.socksx.v5.Socks5AddressDecoder
import io.netty.contrib.handler.codec.socksx.v5.Socks5AddressEncoder
import io.netty.contrib.handler.codec.socksx.v5.Socks5AddressType
import io.netty5.buffer.Buffer
import io.netty5.buffer.BufferAllocator
import io.netty5.buffer.BufferUtil
import io.netty5.util.internal.StringUtil


data class TrojanRequest(val cmd: Byte, val atyp: Byte, val host: String, val port: Int)

data class TrojanPackage(val hexSha224Password: String, val request: TrojanRequest, val payload: String) {
    companion object {
        fun parse(buffer: Buffer): TrojanPackage {
            //hex(SHA224(password)) 56
            val hexSha224Password = BufferUtil.hexDump(buffer.readSplit(56))
            //CRLF  2
            buffer.skipReadableBytes(2)
            //Trojan Request
            //cmd 1
            val cmd = buffer.readByte()
            //atyp 1
            val atyp = buffer.readByte()
            //addr
            val host = Socks5AddressDecoder.DEFAULT.decodeAddress(Socks5AddressType.valueOf(atyp), buffer)
            //port 2
            val port = buffer.readUnsignedShort()
            //CRLF 2
            buffer.skipReadableBytes(2)
            //payload
            val payload = BufferUtil.hexDump(buffer)
            return TrojanPackage(hexSha224Password, TrojanRequest(cmd, atyp, host, port), payload)

        }

        fun toByteBuf(trojanPackage: TrojanPackage): Buffer {
            val out = BufferAllocator.offHeapUnpooled().allocate(1)
            out.writeBytes(trojanPackage.hexSha224Password.toByteArray())
            out.writeBytes(StringUtil.decodeHexDump("0d0a"))
            out.writeByte(trojanPackage.request.cmd.toInt().toByte())
            out.writeByte(trojanPackage.request.atyp.toInt().toByte())
            Socks5AddressEncoder.DEFAULT.encodeAddress(
                Socks5AddressType.valueOf(trojanPackage.request.atyp),
                trojanPackage.request.host,
                out
            )
            out.writeShort(trojanPackage.request.port.toShort())
            out.writeBytes(StringUtil.decodeHexDump("0d0a"))
            out.writeBytes(StringUtil.decodeHexDump(trojanPackage.payload))
            return out

        }
    }
}
