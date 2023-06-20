package rule

import model.config.ConfigurationSettings.Companion.Configuration
import model.config.Inbound
import model.config.Outbound
import model.config.RuleType
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}


fun resolveOutbound(inbound: Inbound, odor: Odor?=null): Optional<Outbound> {

    val matchingRule = Configuration.rules.stream().filter {
        if (RuleType.valueOf(it.type.uppercase()) == RuleType.TAGGED) {
            if (inbound.tag == it.tag) {
                return@filter true
            }
        } else if (RuleType.valueOf(it.type.uppercase()) == RuleType.SNIFFED && odor != null) {
            if (it.protocol != null && Protocol.valueOfOrNull(it.protocol) == odor.desProtocol) {
                return@filter true
            } else if (it.pattern.matcher("${odor.host}:${odor.port}").matches()) {
                return@filter true
            }
        }
        return@filter false
    }.findFirst()

    if (matchingRule.isPresent) {
        val matchingOutbound = Configuration.outbounds.stream()
            .filter { outbound -> matchingRule.get().outboundTag == outbound.tag }.findFirst()
        if (matchingOutbound.isPresent) {
            return matchingOutbound
        }
    }
    logger.debug { "no specify outbound match, use first outbound" }
    return Configuration.outbounds.stream().filter { true }.findFirst()
}
