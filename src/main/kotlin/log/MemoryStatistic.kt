package log

import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.ByteBufAllocatorMetricProvider
import kotlinx.coroutines.delay
import io.github.oshai.kotlinlogging.KotlinLogging


private val logger = KotlinLogging.logger {}
fun memoryStatisticPrint() {
    val metric = (ByteBufAllocator.DEFAULT as ByteBufAllocatorMetricProvider).metric()
    logger.debug("usedDirectMemory: ${metric.usedDirectMemory()}, usedHeapMemory: ${metric.usedHeapMemory()}")

}

suspend fun startMemoryStatisticPrintPerSeconds() {
    while (true) {
        memoryStatisticPrint()
        delay(1000)
    }
}



