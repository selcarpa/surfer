package route

import model.config.ConfigurationSettings
import model.config.Inbound
import model.config.Outbound
import java.util.*

object Route {
    /**
     * todo implement it
     */
    fun resolveOutbound(inbound: Inbound): Optional<Outbound> {
        return ConfigurationSettings.Configuration.outbounds.stream().filter { true }.findFirst()
    }
}
