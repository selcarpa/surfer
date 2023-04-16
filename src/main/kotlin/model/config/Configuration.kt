package model.config

import com.google.gson.Gson
import java.io.File

data class Configuration(val inbounds: List<Inbound>)

data class Inbound(val port: Int, val protocol: String)

class ConfigurationHolder private constructor() {
    companion object {
        var configurationUrl: String? = null
        val configuration: Configuration by lazy { initConfiguration() }

        private fun initConfiguration(): Configuration {
            val gson = Gson()
            return if (configurationUrl.orEmpty().isEmpty()) {
                val content = this::class.java.getResource("/config.json")?.readText()
                // read all string
                gson.fromJson(content, Configuration::class.java)
            } else {
                val content = File(configurationUrl!!).readText()
                gson.fromJson(content, Configuration::class.java)
            }
        }
    }
}
