package rule

import model.config.Config.Configuration
import model.config.Inbound
import model.config.Outbound
import model.config.Rule
import model.config.RuleType
import model.protocol.Odor
import model.protocol.Protocol
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}


fun resolveOutbound(inboundTag: String?, odor: Odor): Optional<Outbound> {
    val matchingRule = Configuration.rules.stream().filter {
        matched(it, inboundTag, odor)
    }.findFirst()

    if (matchingRule.isPresent) {
        val matchingOutbound = Configuration.outbounds.stream()
            .filter { outbound -> matchingRule.get().outboundTag == outbound.tag }.findFirst()
        if (matchingOutbound.isPresent) {
            logger.info { "[${odor.fromChannel}] resolve outbound to ${matchingOutbound.get().tag}" }
            return matchingOutbound
        }
    }
    logger.info { "[${odor.fromChannel}] no specify outbound matched, use first outbound" }
    return Configuration.outbounds.stream().filter { true }.findFirst()
}

private fun matched(it: Rule, inboundTag: String?, odor: Odor): Boolean {
    if (RuleType.valueOf(it.type.uppercase()) == RuleType.TAGGED) {
        if (inboundTag == it.tag) {
            return true
        }
    } else if (RuleType.valueOf(it.type.uppercase()) == RuleType.SNIFFED) {
        if (it.protocol != null && Protocol.valueOfOrNull(it.protocol) == odor.desProtocol) {
            return true
        } else if (it.pattern.matcher("${odor.host}:${odor.port}").matches()) {
            return true
        }
    }
    return false
}
