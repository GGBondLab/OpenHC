package io.github.gcbond.openhc.diagnostics

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.ArrayList

/**
 * 设置页「实时日志」的内存环形缓冲与尾部回填读取器（纯 JVM 逻辑，单元测试直接覆盖）：
 * - [LogRecorder] 写日志时只把原始字符串经 [enqueueRaw] 塞进待处理队列，不做正则脱敏，不阻塞写盘锁；
 * - 界面后台协程按节流周期调 [drainAndSnapshot]，在锁外完成 [DiagnosticsReport.redact] 脱敏和级别分类，
 *   再生成带自增唯一 [Entry.id] 的不可变快照交给 Compose `LazyColumn`。
 */
internal class LiveLogBuffer(val maxLines: Int = DEFAULT_MAX_LINES) {

    class Entry(
        val id: Long,
        val text: String,
        val kind: DiagnosticsReport.LineKind,
    ) {
        val isWarn: Boolean get() = kind == DiagnosticsReport.LineKind.WARN
    }

    private val lock = Any()
    private val pendingRaw = ArrayList<String>()
    private val entries = ArrayList<Entry>(maxLines.coerceAtMost(512))
    private var cachedSnapshot: List<Entry> = emptyList()
    private var nextId = 1L
    private var clearEpoch = 0L
    private var notified = false

    /** 挂载瞬间回填历史尾部行（调用前队列为空）。 */
    fun enqueueInitial(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            val start = (lines.size - maxLines).coerceAtLeast(0)
            for (i in start until lines.size) {
                pendingRaw.add(lines[i])
            }
            trimPendingLocked()
        }
    }

    /**
     * 追加一行实时日志。返回 `true` 表示距离上次界面取数后首次有新日志到达，调用方只需在这时唤醒节流协程，
     * 避免短时间刷出几百行时重复发唤醒信号。
     */
    fun enqueueRaw(line: String): Boolean {
        synchronized(lock) {
            pendingRaw.add(line)
            trimPendingLocked()
            val shouldWake = !notified
            notified = true
            return shouldWake
        }
    }

    /** 暂停状态下查询后台积压了多少行新日志，同时重置唤醒标记以便下一批继续刷新计数。 */
    fun pendingCount(): Int = synchronized(lock) {
        notified = false
        pendingRaw.size
    }

    /** 取出待处理行，在锁外完成地址脱敏与分类后更新环形缓冲，返回当前不可变快照。 */
    fun drainAndSnapshot(): List<Entry> {
        val batch: List<String>
        val epochBefore: Long
        synchronized(lock) {
            notified = false
            if (pendingRaw.isEmpty()) return cachedSnapshot
            batch = ArrayList(pendingRaw)
            pendingRaw.clear()
            epochBefore = clearEpoch
        }
        val processed = ArrayList<Pair<String, DiagnosticsReport.LineKind>>(batch.size)
        for (raw in batch) {
            val redacted = DiagnosticsReport.redact(raw)
            processed.add(redacted to DiagnosticsReport.classifyLine(redacted))
        }
        synchronized(lock) {
            if (clearEpoch != epochBefore) {
                return cachedSnapshot
            }
            for ((text, kind) in processed) {
                entries.add(Entry(nextId++, text, kind))
            }
            val overflow = entries.size - maxLines
            if (overflow > 0) {
                entries.subList(0, overflow).clear()
            }
            val snapshot = ArrayList(entries)
            cachedSnapshot = snapshot
            return snapshot
        }
    }

    /** 清空界面缓冲（只清内存显示，不动磁盘上的日志文件）。 */
    fun clear() {
        synchronized(lock) {
            clearEpoch++
            pendingRaw.clear()
            entries.clear()
            cachedSnapshot = emptyList()
            notified = false
        }
    }

    private fun trimPendingLocked() {
        val overflow = pendingRaw.size - maxLines
        if (overflow > 0) {
            pendingRaw.subList(0, overflow).clear()
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 400
        const val DEFAULT_TAIL_BYTES = 96 * 1024

        /**
         * 从日志文件尾部截取最近 [maxLines] 行（[files] 按旧到新排列，和 [LogRecorder.logFiles] 一致）。
         * 每个文件最多只从末尾读 [maxBytesPerFile] 字节，不把整份 2 MB 文件读进内存；若从文件中间截断，
         * 自动跳过开头半行，保证 UTF-8 多字节中文不会乱码、不会出现半截日志。
         */
        fun readTailRaw(
            files: List<File>,
            maxLines: Int = DEFAULT_MAX_LINES,
            maxBytesPerFile: Int = DEFAULT_TAIL_BYTES,
        ): List<String> {
            if (maxLines <= 0 || files.isEmpty()) return emptyList()
            val chunks = ArrayList<List<String>>(files.size)
            var collected = 0
            for (i in files.indices.reversed()) {
                val part = readSingleFileTail(files[i], maxBytesPerFile)
                if (part.isNotEmpty()) {
                    chunks.add(part)
                    collected += part.size
                    if (collected >= maxLines) break
                }
            }
            if (chunks.isEmpty()) return emptyList()
            val merged = ArrayList<String>(collected.coerceAtMost(maxLines))
            var skip = (collected - maxLines).coerceAtLeast(0)
            for (i in chunks.indices.reversed()) {
                val part = chunks[i]
                if (skip >= part.size) {
                    skip -= part.size
                } else {
                    for (j in skip until part.size) {
                        merged.add(part[j])
                    }
                    skip = 0
                }
            }
            return merged
        }

        private fun readSingleFileTail(file: File, maxBytes: Int): List<String> {
            if (!file.isFile || maxBytes <= 0) return emptyList()
            val bytes: ByteArray
            val truncated: Boolean
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val len = raf.length()
                    if (len <= 0L) return emptyList()
                    val take = minOf(len, maxBytes.toLong()).toInt()
                    val offset = len - take
                    truncated = offset > 0L
                    if (truncated) raf.seek(offset)
                    bytes = ByteArray(take)
                    raf.readFully(bytes)
                }
            } catch (_: IOException) {
                return emptyList()
            }
            var start = 0
            if (truncated) {
                var nl = -1
                for (i in bytes.indices) {
                    if (bytes[i] == '\n'.code.toByte()) {
                        nl = i
                        break
                    }
                }
                if (nl >= 0) {
                    start = nl + 1
                } else {
                    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
                        start++
                    }
                }
            }
            if (start >= bytes.size) return emptyList()
            var text = String(bytes, start, bytes.size - start, Charsets.UTF_8)
            if (text.endsWith("\r\n")) {
                text = text.dropLast(2)
            } else if (text.endsWith("\n")) {
                text = text.dropLast(1)
            }
            if (text.isEmpty()) return emptyList()
            return text.split('\n').map { it.removeSuffix("\r") }
        }
    }
}
