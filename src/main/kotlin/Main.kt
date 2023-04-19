import io.klogging.Level
import io.klogging.config.STDOUT_ANSI
import io.klogging.config.STDOUT_SIMPLE
import io.klogging.config.loggingConfiguration
import model.config.ConfigurationHolder
import netty.NettyServer


fun main(args: Array<String>) {
    loggingConfiguration {
        sink("console", STDOUT_ANSI)
        logging { fromMinLevel(Level.DEBUG) { toSink("console") } }
    }
    args.forEach {
        if (it.startsWith("-c=")) {
            ConfigurationHolder.configurationUrl = it.replace("-c=", "")
        }
    }
    NettyServer().start()
}

