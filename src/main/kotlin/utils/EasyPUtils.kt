package utils

import model.config.ConfigurationHolder
import model.config.Inbound
import model.config.Outbound
import java.util.*

/**
 * todo implement it
 */
object EasyPUtils {
    fun resolveOutbound(inbound: Inbound): Optional<Outbound> {
        return ConfigurationHolder.configuration.outbounds.stream().filter { true }.findFirst()
    }

}
