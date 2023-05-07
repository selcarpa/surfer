package utils

import model.config.ConfigurationSettings.Companion.Configuration
import model.config.Inbound
import model.config.Outbound
import java.util.*

/**
 * todo implement it
 */
object EasyPUtils {
    fun resolveOutbound(inbound: Inbound): Optional<Outbound> {
        return Configuration.outbounds.stream().filter { true }.findFirst()
    }

}
