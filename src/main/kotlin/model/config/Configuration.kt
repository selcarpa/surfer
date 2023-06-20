package model.config

import com.google.gson.Gson
import java.io.File
import java.util.regex.Pattern
import java.util.stream.Collectors
private var rulesOrdered=false
data class ConfigurationSettings(
    val inbounds: List<Inbound>, val outbounds: List<Outbound>
) {

    var rules: List<Rule> = mutableListOf()
        get() {
            if (!rulesOrdered) {
                field = field.stream()
                    .sorted(Comparator.comparing { RuleType.valueOf(it.type.uppercase()).orderMultiple * it.order })
                    .collect(Collectors.toList())
                rulesOrdered=true
                return field
            }
            return field
        }

    var log: LogConfiguration = LogConfiguration()

    companion object {
        var ConfigurationUrl: String? = null
        val Configuration: ConfigurationSettings by lazy { initConfiguration() }

        private fun initConfiguration(): ConfigurationSettings {
            val gson = Gson()
            return if (ConfigurationUrl.orEmpty().isEmpty()) {
                val content = this::class.java.getResource("/config.json5")?.readText()
                // read all string
                gson.fromJson(content, ConfigurationSettings::class.java)
            } else {
                val content = File(ConfigurationUrl!!).readText()
                gson.fromJson(content, ConfigurationSettings::class.java)
            }
        }
    }
}
data class Inbound(val port: Int, val protocol: String, val inboundStreamBy: InboundStreamBy?, val socks5Setting: Socks5Setting?, val trojanSetting: TrojanSetting?, val tag:String?)
data class Socks5Setting(val auth: Auth?)
data class Auth(val password: String, val username: String)
data class Outbound(val protocol: String, val trojanSetting: TrojanSetting?, val outboundStreamBy: OutboundStreamBy?, val tag:String?)
data class OutboundStreamBy(val type: String, val wsOutboundSetting: WsOutboundSetting?,val sock5OutboundSetting: Sock5OutboundSetting?,val httpOutboundSetting: HttpOutboundSetting?,val tcpOutboundSetting:TcpOutboundSetting?)
data class Sock5OutboundSetting(val auth: Auth?,val port:Int,val host:String)
data class HttpOutboundSetting(val auth: Auth?,val port:Int,val host:String)
data class TcpOutboundSetting(val port: Int, val host: String)
data class InboundStreamBy(val type: String, val wsInboundSetting: WsInboundSetting)
data class WsOutboundSetting(val path: String, val port: Int, val host: String)
data class WsInboundSetting(val path: String)
data class TrojanSetting(val password: String)
data class LogConfiguration(var level: String = "info", var pattern: String = "%date{ISO8601} %highlight(%level) [%t] %cyan(%logger{16}) %M: %msg%n", var maxHistory: Int = 7, var fileName: String = "", var path: String = "./logs/")
data class Rule(val type: String, val tag: String?, val protocol: String?, val order: Int, val destPattern: String?) {
    val pattern: Pattern by lazy { loadPattern() }
    private fun loadPattern(): Pattern {
        if (destPattern == null) {
            throw Exception("destPattern is null")
        }
        return Pattern.compile(destPattern)
    }
}

enum class RuleType(val orderMultiple: Int = 1) {
    TAGGED, SNIFFED(1000)
}
