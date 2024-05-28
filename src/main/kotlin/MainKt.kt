import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import log.loadLogConfig
import log.startMemoryStatisticPrintPerSeconds
import log.startSpeedStatisticPrintPerSeconds
import model.config.Config.ConfigurationUrl
import netty.NettyServer

private val logger = KotlinLogging.logger {}

object MainKt {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        args.forEach {
            argCommandExec(it)
        }

        loadLogConfig()

        NettyServer.start()
        logger.info { "【注意事项】 本品不能代替药物。" }
    }
}

val argCommands: List<ArgCommand> = listOf(
    // configuration load
    ArgCommand({ it.startsWith("-c=") }, { ConfigurationUrl = it.replace("-c=", "") }, description = """
        Set configuration file path, support json and toml file
    """.trimIndent()
    ),
    // memory statistic print
    ArgCommand({ it == "-memstat" },
        coroutineExec = { run { startMemoryStatisticPrintPerSeconds() } },
        description = """
        To print memory statistic per seconds
    """.trimIndent()
    ),
    // speed statistic print
    ArgCommand({ it == "-speedstat" },
        coroutineExec = { run { startSpeedStatisticPrintPerSeconds() } },
        description = """
        To print speed statistic per seconds
    """.trimIndent()
    )

)

data class ArgCommand(
    val check: (String) -> Boolean,
    val exec: ((String) -> Unit)? = null,
    val coroutineExec: (suspend (String) -> Unit)? = null,
    val description: String
)

fun argCommandExec(arg: String) = runBlocking {
    argCommands.forEach {
        if (it.check(arg)) {
            it.exec?.invoke(arg)
            launch {
                it.coroutineExec?.invoke(arg)
            }
        }
    }
}
