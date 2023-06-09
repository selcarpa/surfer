package model.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.regex.Pattern
import java.util.stream.Collectors

private var rulesOrdered = false

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    isLenient = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

//val toml = Toml {
//    ignoreUnknownKeys = true
//}
object Config {
    var ConfigurationUrl: String? = null
    val Configuration: ConfigurationSettings by lazy { initConfiguration() }

    private fun initConfiguration(): ConfigurationSettings {
        return if (ConfigurationUrl.orEmpty().isEmpty()) {
            val content = this::class.java.getResource("/config.json5")?.readText()
            json.decodeFromString<ConfigurationSettings>(content!!)
        } else {
            val file = File(ConfigurationUrl!!)
            val content = file.readText()
            if (file.name.endsWith("json") || file.name.endsWith("json5")) {
                json.decodeFromString<ConfigurationSettings>(content)
            }/* else if (file.name.endsWith("toml")) {
                toml.decodeFromString<ConfigurationSettings>(content)
            }*/ else {
                throw Exception("not support file type")
            }
        }
    }
}

@Serializable
data class ConfigurationSettings(
    val inbounds: List<Inbound>, val outbounds: List<Outbound>
) {
    var rules: List<Rule> = mutableListOf()
        get() {
            if (!rulesOrdered) {
                field = field.stream()
                    .sorted(Comparator.comparing { RuleType.valueOf(it.type.uppercase()).orderMultiple * it.order })
                    .collect(Collectors.toList())
                rulesOrdered = true
                return field
            }
            return field
        }

    var log: LogConfiguration = LogConfiguration()
}

@Serializable
data class Inbound(val port: Int, val protocol: String, val inboundStreamBy: InboundStreamBy?, val socks5Setting: Socks5Setting?, val trojanSetting: TrojanSetting?, val tag:String?)
@Serializable
data class Socks5Setting(val auth: Auth?)
@Serializable
data class Auth(val password: String, val username: String)
@Serializable
data class Outbound(val protocol: String, val trojanSetting: TrojanSetting?, val outboundStreamBy: OutboundStreamBy?, val tag:String?)
@Serializable
data class OutboundStreamBy(val type: String, val wsOutboundSetting: WsOutboundSetting?,val sock5OutboundSetting: Sock5OutboundSetting?,val httpOutboundSetting: HttpOutboundSetting?,val tcpOutboundSetting:TcpOutboundSetting?)
@Serializable
data class Sock5OutboundSetting(val auth: Auth?,val port:Int,val host:String)
@Serializable
data class HttpOutboundSetting(val auth: Auth?,val port:Int,val host:String)
@Serializable
data class TcpOutboundSetting(val port: Int, val host: String)
@Serializable
data class InboundStreamBy(val type: String, val wsInboundSetting: WsInboundSetting?,val tlsInboundSetting: TlsInboundSetting? )
@Serializable
data class TlsInboundSetting(val keyCertChainFile:String, val keyFile:String, val password: String?)
@Serializable
data class WsOutboundSetting(val path: String, val port: Int, val host: String)
@Serializable
data class WsInboundSetting(val path: String)
@Serializable
data class TrojanSetting(val password: String)
@Serializable
data class LogConfiguration(var level: String = "info", var pattern: String = "%date{ISO8601} %highlight(%level) [%t] %cyan(%logger{16}) %M: %msg%n", var maxHistory: Int = 7, var fileName: String = "", var path: String = "./logs/")
@Serializable
data class Rule(val type: String, val tag: String?, val protocol: String?, val order: Int, val destPattern: String?,val outboundTag:String) {
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
