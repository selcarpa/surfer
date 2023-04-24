import io.klogging.Level
import io.klogging.NoCoLogging
import io.klogging.config.STDOUT_ANSI
import io.klogging.config.loggingConfiguration
import model.config.ConfigurationHolder
import netty.NettyServer

object Main : NoCoLogging {
    @JvmStatic
    fun main(args: Array<String>) {
        loggingConfiguration {
            sink("console", STDOUT_ANSI)
            logging { fromMinLevel(Level.DEBUG) { toSink("console") } }
        }
        logger.info("『时间』会带来喜悦。")
        args.forEach {
            if (it.startsWith("-c=")) {
                ConfigurationHolder.configurationUrl = it.replace("-c=", "")
            }
        }
        NettyServer().start()
    }


}
