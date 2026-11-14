package com.shimmer.reader.help

import com.shimmer.reader.data.entities.BaseSource
import com.shimmer.reader.exception.ConcurrentException
import com.shimmer.reader.help.config.AppConfig
import com.shimmer.reader.model.analyzeRule.AnalyzeUrl.ConcurrentRecord
import com.shimmer.reader.utils.NetworkUtils
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class ConcurrentRateLimiter(source: BaseSource?) {

    companion object {
        val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()

        /**
         * 限速记录用哪个 key。
         *
         * 关闭共用时一个书源一份记录，和原来完全一致；
         * 打开后同一个主域、同一个速率的书源共用一份配额。
         *
         * 速率不同不合并：配额是一份令牌池，把 1/3s 和 2/1s 并在一起，
         * 快的那个会被慢的拖住，而慢的那个也不会因此变快，没有意义。
         */
        fun recordKey(
            sourceUrl: String?,
            concurrentRate: String?,
            shareByHost: Boolean,
            hostOf: (String) -> String?,
        ): String? {
            val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
            if (!shareByHost) return url
            val host = hostOf(url)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return url
            return "host:$host#${concurrentRate.orEmpty()}"
        }

        /**
         * 按当前设置算这个书源该用哪个记录 key。
         *
         * 书源编辑里改并发率时也要用同一套算法，否则会往一个没人用的 key 上写。
         */
        fun keyOf(url: String?, rate: String?): String? = recordKey(
            sourceUrl = url,
            concurrentRate = rate,
            shareByHost = AppConfig.shareRateLimitByHost,
            hostOf = NetworkUtils::getSubDomainOrNull
        )

        /**
         * 更新并发率
         */
        fun updateConcurrentRate(key: String, concurrentRate: String) {
            concurrentRecordMap.compute(key) { _, record ->
                try {
                    val rateIndex = concurrentRate.indexOf("/")
                    when {
                        rateIndex > 0 -> {
                            val accessLimit = concurrentRate.take(rateIndex).toInt()
                            val interval = concurrentRate.substring(rateIndex + 1).toInt()
                            if (accessLimit <= 0 || interval <= 0) throw NumberFormatException()
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                accessLimit,
                                interval,
                                record?.frequency ?: 0
                            )
                        }
                        concurrentRate.toInt() > 0 -> {
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                1,
                                concurrentRate.toInt(),
                                record?.frequency ?: 0
                            )
                        }
                        else -> record
                    }
                } catch (_: NumberFormatException) {
                    record
                }
            }
        }
    }

    private val concurrentRate = source?.concurrentRate
    private val key = keyOf(source?.getKey(), concurrentRate)
    /**
     * 开始访问,并发判断
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        if (concurrentRate.isNullOrEmpty() || concurrentRate == "0") {
            return null
        }
        val key = key ?: return null
        var isNewRecord = false
        val fetchRecord = concurrentRecordMap.computeIfAbsent(key) {
            isNewRecord = true
            val rateIndex = concurrentRate.indexOf("/")
            if (rateIndex > 0) {
                val accessLimit = concurrentRate.take(rateIndex).toIntOrNull() ?: 1
                val interval = concurrentRate.substring(rateIndex + 1).toIntOrNull() ?: 0
                ConcurrentRecord(System.currentTimeMillis(), accessLimit, interval, 1)
            }
            else {
                ConcurrentRecord(System.currentTimeMillis(),1,concurrentRate.toIntOrNull() ?: 0, 1)
            }
        }
        if (isNewRecord) return fetchRecord
        val waitTime: Long = synchronized(fetchRecord) {
            //并发控制为 次数/毫秒 , 非并发实际为1/毫秒
            val nextTime = fetchRecord.time + fetchRecord.interval.toLong()
            val nowTime = System.currentTimeMillis()
            if (nowTime >= nextTime) {
                //已经过了限制时间,重置开始时间
                fetchRecord.time = nowTime
                fetchRecord.frequency = 1
                return@synchronized 0
            }
            if (fetchRecord.frequency < fetchRecord.accessLimit) {
                fetchRecord.frequency ++
                return@synchronized 0
            } else {
                return@synchronized nextTime - nowTime
            }
        }
        if (waitTime > 0) {
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime
            )
        }
        return fetchRecord
    }

    /**
     * 获取并发记录，若处于并发限制状态下则会等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime)
            }
        }
    }

    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                Thread.sleep(e.waitTime)
            }
        }
    }

    suspend inline fun <T> withLimit(block: () -> T): T {
        getConcurrentRecord()
        return block()
    }

    inline fun <T> withLimitBlocking(block: () -> T): T {
        getConcurrentRecordBlocking()
        return block()
    }

}
