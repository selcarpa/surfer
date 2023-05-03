
import model.config.ConfigurationHolder
import mu.KotlinLogging
import netty.NettyServer

private val logger = KotlinLogging.logger {}

object MainKt {
    @JvmStatic
    fun main(args: Array<String>) {
        args.forEach {
            if (it.startsWith("-c=")) {
                ConfigurationHolder.configurationUrl = it.replace("-c=", "")
            }
        }
        NettyServer().start()
        logger.info("『时间』会带来喜悦。")
    }


}
