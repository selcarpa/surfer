package model.config

import com.google.gson.Gson
import java.io.File

data class Configuration(val inbounds: List<Inbound>, val outbounds: List<Outbound>)
data class Inbound(
    val port: Int,
    val protocol: String,
    val inboundStreamBy: InboundStreamBy?,
    val socks5Setting: Socks5Setting?
)

data class Socks5Setting(val auth: Auth?)
data class Auth(val password: String, val username: String)
data class Outbound(val protocol: String, val trojanSetting: TrojanSetting?, val outboundStreamBy: OutboundStreamBy)
data class OutboundStreamBy(val type: String, val wsOutboundSettings: List<WsOutboundSetting>)
data class InboundStreamBy(val type: String, val wsInboundSettings: List<WsInboundSetting>)
data class WsOutboundSetting(val path: String, val port: Int, val host: String)
data class WsInboundSetting(val path: String)
data class TrojanSetting(val password: String) : ProxyProtocolSetting()
open class ProxyProtocolSetting()

class ConfigurationHolder private constructor() {
    companion object {
        var configurationUrl: String? = null
        val configuration: Configuration by lazy { initConfiguration() }

        private fun initConfiguration(): Configuration {
            val gson = Gson()
            return if (configurationUrl.orEmpty().isEmpty()) {
                val content = this::class.java.getResource("/config.json5")?.readText()
                // read all string
                gson.fromJson(content, Configuration::class.java)
            } else {
                val content = File(configurationUrl!!).readText()
                gson.fromJson(content, Configuration::class.java)
            }
        }
    }
}
