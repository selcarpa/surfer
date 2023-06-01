import log.loadLogConfig
import model.config.ConfigurationSettings.Companion.ConfigurationUrl
import mu.KotlinLogging
import netty.NettyServer

private val logger = KotlinLogging.logger {}

object MainKt {
    @JvmStatic
    fun main(args: Array<String>) {
        args.forEach {
            if (it.startsWith("-c=")) {
                ConfigurationUrl = it.replace("-c=", "")
            }
        }

        loadLogConfig()

        NettyServer.start()
        logger.info("『时间』会带来喜悦。")
    }


}
