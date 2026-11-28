package io.github.gcbond.openhc.diagnostics

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveLogBufferTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun bufferRedactsClassifiesCapsAndPreservesStableIds() {
        val buf = LiveLogBuffer(maxLines = 3)
        buf.enqueueInitial(
            listOf(
                "01-02 03:04:05.001  1000  1001 I OpenHC: connect 11:22:33:44:55:66",
                "01-02 03:04:05.002  1000  1001 W OpenHC: retry",
            ),
        )
        val first = buf.drainAndSnapshot()
        assertEquals(2, first.size)
        assertEquals("01-02 03:04:05.001  1000  1001 I OpenHC: connect 11:**:**:**:55:66", first[0].text)
        assertFalse(first[0].isWarn)
        assertTrue(first[1].isWarn)
        val retryId = first[1].id

        assertTrue(buf.enqueueRaw("01-02 03:04:05.003  1000  1001 D OpenHC: step 3"))
        assertFalse(buf.enqueueRaw("01-02 03:04:05.004  1000  1001 E OpenHC: boom aa:bb:cc:dd:ee:ff"))
        assertEquals(2, buf.pendingCount())
        // pendingCount() 重置唤醒位，下一条到达会再次触发唤醒。
        assertTrue(buf.enqueueRaw("===== 01-02 03:04:05.005 OpenHC 诊断记录：进程 1000 开始记录 ====="))

        val second = buf.drainAndSnapshot()
        assertEquals(3, second.size)
        assertEquals("01-02 03:04:05.003  1000  1001 D OpenHC: step 3", second[0].text)
        assertEquals("01-02 03:04:05.004  1000  1001 E OpenHC: boom aa:**:**:**:ee:ff", second[1].text)
        assertTrue(second[1].isWarn)
        assertEquals(DiagnosticsReport.LineKind.MARKER, second[2].kind)
        assertTrue(second[0].id > retryId)

        buf.clear()
        assertEquals(0, buf.pendingCount())
        assertTrue(buf.drainAndSnapshot().isEmpty())
    }

    @Test
    fun readTailRawMergesRotatedFilesAndSkipsBrokenUtf8Prefix() {
        val prev = temp.newFile("log.1.txt")
        val curr = temp.newFile("log.txt")
        prev.writeText(
            (1..5).joinToString("\n", postfix = "\n") { "旧文件第 $it 行中文测试" },
            Charsets.UTF_8,
        )
        curr.writeText(
            "新文件第 1 行中文\n新文件第 2 行中文\n",
            Charsets.UTF_8,
        )

        val merged = LiveLogBuffer.readTailRaw(listOf(prev, curr), maxLines = 4)
        assertEquals(
            listOf("旧文件第 4 行中文测试", "旧文件第 5 行中文测试", "新文件第 1 行中文", "新文件第 2 行中文"),
            merged,
        )

        // 限制读取字节数，强制从多字节中文字符中间截断，验证自动跳过半行且无乱码。
        val longFile = File(temp.root, "big.txt")
        val content = (1..20).joinToString("\n", postfix = "\n") { "第${it.toString().padStart(2, '0')}行：比亚迪车机实时日志滚动测试内容" }
        longFile.writeText(content, Charsets.UTF_8)
        val tail = LiveLogBuffer.readTailRaw(listOf(longFile), maxLines = 5, maxBytesPerFile = 220)
        assertTrue(tail.isNotEmpty())
        assertTrue(tail.size <= 5)
        assertEquals("第20行：比亚迪车机实时日志滚动测试内容", tail.last())
        for (line in tail) {
            assertFalse('\uFFFD' in line, "不应出现 UTF-8 截断乱码：$line")
            assertTrue(line.startsWith("第"), "不应出现半截残行：$line")
        }
    }
}
