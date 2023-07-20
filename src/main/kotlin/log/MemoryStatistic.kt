//package log
//
//import io.netty5.buffer.ByteBufAllocator
//import io.netty5.buffer.ByteBufAllocatorMetricProvider
//import mu.KotlinLogging
//import java.util.concurrent.Executors
//
//
//private val logger = KotlinLogging.logger {}
//fun memoryStatisticPrint() {
//    val metric = (ByteBufAllocator.DEFAULT as ByteBufAllocatorMetricProvider).metric()
//    logger.debug("usedDirectMemory: ${metric.usedDirectMemory()}, usedHeapMemory: ${metric.usedHeapMemory()}")
//
//}
//
//fun startMemoryStatisticPrint() {
//    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
//        { memoryStatisticPrint() }, 0, 1, java.util.concurrent.TimeUnit.SECONDS
//    )
//}
//
//
//
