package com.wludy.rolithax.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 实例图标缓存：内存 LRU（6MB）+ 磁盘（cacheDir/instance_icons，TTL 24 小时）
 * + 并发合并 + 失败负缓存（60s），保证列表滚动与 15s 轮询都不重复请求。
 *
 * [load] 是唯一入口：缓存命中直接返回；未命中时执行 [loader]（网络或本地文件），
 * 成功后同时写入内存与磁盘；失败优先退回过期的磁盘缓存（好过只剩占位图），
 * 最后才记负缓存。同一 key 的并发加载用一把互斥锁合并，等锁期间双检缓存。
 */
object InstanceIconStore {

    private const val MEMORY_CACHE_BYTES = 6 * 1024 * 1024
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 16_384
    private const val MAX_IMAGE_PIXELS = 100_000_000L
    private const val MAX_DECODED_DIMENSION = 256
    private const val DISK_TTL_MS = 24 * 60 * 60 * 1000L
    private const val NEGATIVE_TTL_MS = 60_000L

    @Volatile
    private var appContext: Context? = null

    private val memoryCache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    /** 加载失败（确认无图标）的负缓存：key -> 过期时刻；避免轮询周期内反复打 Daemon。 */
    private val negativeUntil = ConcurrentHashMap<String, Long>()

    /** 每个 key 一把互斥锁（key 数量 = 实例数，规模有限，无需淘汰）。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun readFile(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_ICON_BYTES.toLong()) return@runCatching null
        file.inputStream().use(::readLimited)
    }.getOrNull()

    /** 取图标；[loader] 仅在缓存未命中时调用，返回 PNG 字节（null = 无图标）。 */
    suspend fun load(key: String, loader: suspend () -> ByteArray?): Bitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(key)?.let { return@withContext it }
        negativeUntil[key]?.let { until ->
            if (System.currentTimeMillis() < until) return@withContext null
            negativeUntil.remove(key)
        }

        val mutex = locks.computeIfAbsent(key) { Mutex() }
        mutex.withLock {
            // 双检：等锁期间可能已被其它调用填好
            memoryCache.get(key)?.let { return@withLock it }
            readFreshDisk(key)?.let { return@withLock it }

            val bytes = runCatching { loader() }.getOrNull()
            bytes?.let { decode(it) }?.let { bitmap ->
                memoryCache.put(key, bitmap)
                writeDisk(key, bytes)
                return@withLock bitmap
            }
            readStaleDisk(key)?.let { return@withLock it }
            negativeUntil[key] = System.currentTimeMillis() + NEGATIVE_TTL_MS
            null
        }
    }

    private fun readFreshDisk(key: String): Bitmap? {
        val file = diskFile(key) ?: return null
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > DISK_TTL_MS) return null
        return runCatching { readFile(file)?.let(::decode) }.getOrNull()
    }

    /** 过期的磁盘缓存：仅作为加载失败时的兜底，命中后同样进内存缓存。 */
    private fun readStaleDisk(key: String): Bitmap? {
        val file = diskFile(key) ?: return null
        if (!file.isFile) return null
        return runCatching { readFile(file)?.let(::decode) }.getOrNull()?.also { memoryCache.put(key, it) }
    }

    private fun writeDisk(key: String, bytes: ByteArray) {
        val file = diskFile(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    /** 磁盘文件：key 经安全字符替换并限长，附哈希后缀防替换后同名冲突。 */
    private fun diskFile(key: String): File? {
        val context = appContext ?: return null
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return File(File(context.cacheDir, "instance_icons"), "$safe-${key.hashCode()}.png")
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > MAX_ICON_BYTES - total) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_IMAGE_DIMENSION || height !in 1..MAX_IMAGE_DIMENSION) return null
        if (width.toLong() * height > MAX_IMAGE_PIXELS) return null
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > MAX_DECODED_DIMENSION) sampleSize *= 2
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }.getOrNull()
    }
}
