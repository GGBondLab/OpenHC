package io.github.gcbond.openhc.diagnostics

import java.util.Locale

/** 诊断日志文件的组成（纯逻辑，JVM 单元测试覆盖）：文件名、开头的说明和摘要、各段的标题。 */
internal object DiagnosticsReport {
    /** 存到「下载」里的子文件夹名。 */
    const val FOLDER = "OpenHC"

    /** [timestamp] 形如 `20261122-201530`。 */
    fun fileName(timestamp: String): String = "openhc-diagnostics-$timestamp.txt"

    /** 文件开头：一段说明，再一行一项的摘要（项目：值）。 */
    fun header(facts: List<Pair<String, String>>): String = buildString {
        append("OpenHC 诊断日志\n")
        append("只有 OpenHC 自己的运行记录：不含连接密钥和 WiFi 密码，手机、车机的蓝牙和网卡地址只留首尾几位。\n")
        append("报告问题时把整个文件附上即可。\n\n")
        for ((name, value) in facts) append(name).append("：").append(value.ifBlank { "（空）" }).append('\n')
    }

    fun section(title: String): String = "\n===== $title =====\n"

    /**
     * 导出前逐行处理：完整的蓝牙 / 网卡地址只留首尾几位（和 OpenHC 自己打日志时的写法一样）。OpenHC 自己本来就不打完整地址，
     * 这里兜住系统在本进程里打的日志（各家车机系统打什么不一定）。占位地址（全 0、02:00:00:00:00:00、全 f）不认人，原样留着。
     */
    fun redact(line: String): String {
        if (!hasPotentialMac(line)) return line
        return MAC.replace(line) { m ->
            val mac = m.value
            if (mac.lowercase(Locale.ROOT) in KEEP) mac else mac.substring(0, 2) + ":**:**:**:" + mac.substring(12)
        }
    }

    /** 快速扫描是否存在 `XX:XX:XX:XX:XX:XX` 形态的冒号组，普通无地址日志直接跳过带双负向回顾的正则匹配。 */
    private fun hasPotentialMac(line: String): Boolean {
        val limit = line.length - 14
        var c = 2
        while (c < limit) {
            val idx = line.indexOf(':', c)
            if (idx < 0 || idx >= limit) return false
            if (line[idx + 3] == ':' && line[idx + 6] == ':' && line[idx + 9] == ':' && line[idx + 12] == ':' &&
                isHex(line[idx - 2]) && isHex(line[idx - 1]) && isHex(line[idx + 1])
            ) {
                return true
            }
            c = idx + 1
        }
        return false
    }

    private fun isHex(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    /**
     * 这一行（`logcat -v threadtime` 格式）是不是 [pid] 这个进程打的。不是日志行的（例如「--------- beginning of main」）算是。
     * 安卓 7.0 以下 logcat 不能按进程号过滤，靠这个保证只记本进程的日志（不依赖系统只给应用看自己日志的限制）。
     */
    fun fromProcess(line: String, pid: String): Boolean {
        val m = THREADTIME_PID.find(line) ?: return true
        return m.groupValues[1] == pid
    }

    /**
     * OpenHC 自己的一条日志写成 `logcat -v threadtime` 的格式（和读 logcat 记下的行一样），多行的消息和堆栈每行都带前缀。
     * [time] 形如 `01-02 03:04:05.678`；[priority] 是安卓日志级别（2 VERBOSE ～ 7 ASSERT）。
     */
    fun ownLines(time: String, pid: Int, tid: Int, priority: Int, tag: String, message: String, stack: String?): List<String> {
        val level = levelChar(priority)
        if (stack.isNullOrBlank() && message.indexOf('\n') < 0 && message.indexOf('\r') < 0) {
            val sb = StringBuilder(time.length + tag.length + message.length + 18)
            appendPrefix(sb, time, pid, tid, level, tag)
            sb.append(message)
            return java.util.Collections.singletonList(sb.toString())
        }
        val prefixBuilder = StringBuilder(time.length + tag.length + 18)
        appendPrefix(prefixBuilder, time, pid, tid, level, tag)
        val prefix = prefixBuilder.toString()
        val text = if (stack.isNullOrBlank()) message else message + "\n" + stack.trimEnd()
        return text.split('\n').map { prefix + it.removeSuffix("\r") }
    }

    private fun appendPrefix(sb: StringBuilder, time: String, pid: Int, tid: Int, level: Char, tag: String) {
        sb.append(time).append(' ')
        appendPadded5(sb, pid)
        sb.append(' ')
        appendPadded5(sb, tid)
        sb.append(' ').append(level).append(' ').append(tag).append(": ")
    }

    private fun appendPadded5(sb: StringBuilder, value: Int) {
        val s = value.toString()
        for (i in s.length until 5) sb.append(' ')
        sb.append(s)
    }

    /**
     * `logcat` 读到的这一行是不是 OpenHC 自己的日志（已经直接记过，读 logcat 时跳过，免得记两遍）：标签是 `OpenHC` 或
     * `OpenHC-…`。内核的权限拒绝记录（`type=1400 audit…`）会拿线程名当标签，本应用的线程名也是 `OpenHC-…`，那些不是
     * 自己打的，照记。
     */
    fun writtenDirectly(line: String): Boolean {
        val levelIdx = threadtimeLevelIndex(line)
        if (levelIdx < 0) return false
        val level = line[levelIdx]
        if ("VDIWEFAS".indexOf(level) < 0) return false
        val tagStart = levelIdx + 2
        val colon = line.indexOf(':', tagStart)
        if (colon <= tagStart) return false
        var tagEnd = colon
        while (tagEnd > tagStart && line[tagEnd - 1] == ' ') tagEnd--
        val tagLen = tagEnd - tagStart
        val ownLen = OWN_TAG.length
        if (tagLen < ownLen || !line.regionMatches(tagStart, OWN_TAG, 0, ownLen)) return false
        if (tagLen > ownLen && line[tagStart + ownLen] != '-') return false
        var msgStart = colon + 1
        if (msgStart < line.length && line[msgStart] == ' ') msgStart++
        return !line.startsWith("type=1400", msgStart)
    }

    /** 实时日志框里一行的视觉分类：普通行、警告/错误行（W/E/F/A）、诊断分节标记行（===== … =====）。 */
    enum class LineKind { NORMAL, WARN, MARKER }

    /**
     * 判断一行运行日志的类型（供设置页实时日志框着色和「只看异常」筛选）：
     * - `===== … =====` 诊断记录标记行算 [LineKind.MARKER]（其中写明丢日志或退出的算 [LineKind.WARN]）；
     * - `logcat -v threadtime` 行中级别为 `W`/`E`/`F`/`A` 算 [LineKind.WARN]；
     * - 其余行算 [LineKind.NORMAL]。
     */
    fun classifyLine(line: String): LineKind {
        if (line.startsWith("===== ") && line.endsWith(" =====")) {
            return if ("丢了" in line || "退出了" in line) LineKind.WARN else LineKind.MARKER
        }
        val idx = threadtimeLevelIndex(line)
        return if (idx >= 0) {
            when (line[idx]) {
                'W', 'E', 'F', 'A' -> LineKind.WARN
                else -> LineKind.NORMAL
            }
        } else {
            LineKind.NORMAL
        }
    }

    /** 快速定位 `MM-dd HH:mm:ss.SSS  PID  TID L …` 里级别字符的下标，不匹配返回 -1，不分配正则对象。 */
    private fun threadtimeLevelIndex(line: String): Int {
        val n = line.length
        if (n < 22 || line[2] != '-' || line[5] != ' ' || line[8] != ':' || line[11] != ':' || line[14] != '.') {
            return -1
        }
        var i = 15
        if (i >= n || line[i] !in '0'..'9') return -1
        while (i < n && line[i] in '0'..'9') i++
        if (i >= n || line[i] != ' ') return -1
        while (i < n && line[i] == ' ') i++
        if (i >= n || line[i] !in '0'..'9') return -1
        while (i < n && line[i] in '0'..'9') i++
        if (i >= n || line[i] != ' ') return -1
        while (i < n && line[i] == ' ') i++
        if (i >= n || line[i] !in '0'..'9') return -1
        while (i < n && line[i] in '0'..'9') i++
        if (i + 2 >= n || line[i] != ' ' || line[i + 2] != ' ') return -1
        return i + 1
    }

    private fun levelChar(priority: Int): Char = when (priority) {
        2 -> 'V'
        3 -> 'D'
        4 -> 'I'
        5 -> 'W'
        6 -> 'E'
        7 -> 'A'
        else -> '?'
    }

    private const val OWN_TAG = "OpenHC"

    private val THREADTIME_PID = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+(\\d+)\\s+\\d+\\s")

    /** 冒号分隔的 6 段地址；前后紧挨着别的十六进制段的不算（例如更长的 IPv6 地址）。 */
    private val MAC = Regex("(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]:)[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?!:?[0-9A-Fa-f])")

    private val KEEP = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "ff:ff:ff:ff:ff:ff")
}
