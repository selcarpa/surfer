package model.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder
import io.netty.handler.codec.socksx.v5.Socks5AddressType

data class TrojanRequest(val cmd: Byte, val atyp: Byte, val host: String, val port: Int)

data class TrojanPackage(val hexSha224Password: String, val request: TrojanRequest, val payload: String) {
    companion object {
        fun parse(byteBuf: ByteBuf): TrojanPackage {
            //hex(SHA224(password)) 56
            val hexSha224Password = ByteBufUtil.hexDump(byteBuf.readSlice(56))
            //CRLF  2
            byteBuf.skipBytes(2)
            //Trojan Request
            //cmd 1
            val cmd = byteBuf.readByte()
            //atyp 1
            val atyp = byteBuf.readByte()
            //addr
            val host = Socks5AddressDecoder.DEFAULT.decodeAddress(Socks5AddressType.valueOf(atyp), byteBuf)
            //port 2
            val port = byteBuf.readUnsignedShort()
            //CRLF 2
            byteBuf.skipBytes(2)
            //payload
            val payload = ByteBufUtil.hexDump(byteBuf)
            return TrojanPackage(hexSha224Password, TrojanRequest(cmd, atyp, host, port), payload)

        }

        fun toByteBuf(trojanPackage: TrojanPackage): ByteBuf {
            val out = Unpooled.buffer()
            out.writeBytes(trojanPackage.hexSha224Password.toByteArray())
            out.writeBytes(ByteBufUtil.decodeHexDump("0d0a"))
            out.writeByte(trojanPackage.request.cmd.toInt())
            out.writeByte(trojanPackage.request.atyp.toInt())
            Socks5AddressEncoder.DEFAULT.encodeAddress(
                Socks5AddressType.valueOf(trojanPackage.request.atyp),
                trojanPackage.request.host,
                out
            )
            out.writeShort(trojanPackage.request.port)
            out.writeBytes(ByteBufUtil.decodeHexDump("0d0a"))
            out.writeBytes(ByteBufUtil.decodeHexDump(trojanPackage.payload))
            return out

        }
    }
}
