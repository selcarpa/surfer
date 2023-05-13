package utils

import model.config.ConfigurationSettings.Companion.Configuration
import model.config.Inbound
import model.config.Outbound
import java.util.*

object SurferUtils {
    /**
     * todo implement it
     */
    fun resolveOutbound(inbound: Inbound): Optional<Outbound> {
        return Configuration.outbounds.stream().filter { true }.findFirst()
    }

}
