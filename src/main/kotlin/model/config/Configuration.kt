package model.config

import com.google.gson.Gson
import java.io.File

data class Configuration(val inbounds: List<Inbound>, val outbounds: List<Outbound>)
data class Inbound(val port: Int, val protocol: String, val socks5Settings: List<Socks5Setting>)
data class Socks5Setting(val auth: List<Auth>)
data class Auth(val password: String, val user: String)
data class Outbound(val protocol: String, val trojanSetting: TrojanSetting?, val streamBy: StreamBy)
data class StreamBy(val type: String, val wsSettings: List<WsSetting>)
data class WsSetting(val path: String, val port: Int, val host: String)
data class TrojanSetting(val password: String):ProxyProtocolSetting()
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
