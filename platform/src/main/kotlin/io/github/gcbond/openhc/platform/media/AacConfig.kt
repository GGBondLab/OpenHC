package io.github.gcbond.openhc.platform.media

/**
 * AAC 的两段配置（纯逻辑）：
 *
 * - **AudioSpecificConfig**（MediaCodec 的 `csd-0`，ISO/IEC 14496-3）：对象类型（5 位）‖ 采样率索引（4 位，
 *   不在表里时写 15 再跟 24 位采样率）‖ 声道配置（4 位）‖ 3 个 0 位，按字节补齐。AAC-LC 48kHz 双声道是 `11 90`。
 * - **ADTS 头**（ISO/IEC 13818-7）：同步字 0xFFF、layer 00，7 字节（带 CRC 时 9 字节），里面有对象类型、
 *   采样率索引、声道配置和整帧长度。载荷带 ADTS 头时去掉头再送解码器，配置以头里的为准。
 */
object AacConfig {
    /** AAC-LC 的对象类型。 */
    const val OBJECT_TYPE_LC = 2

    /** 每个 AAC 帧解出的采样数（AAC-LC）。 */
    const val SAMPLES_PER_FRAME = 1024

    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** 采样率在标准表里的索引；不在表里返回 -1。 */
    fun sampleRateIndex(sampleRate: Int): Int = SAMPLE_RATES.indexOf(sampleRate)

    fun audioSpecificConfig(sampleRate: Int, channels: Int, objectType: Int = OBJECT_TYPE_LC): ByteArray {
        require(objectType in 1..30) { "对象类型超出范围: $objectType" }
        require(channels in 1..7) { "声道配置超出范围: $channels" }
        require(sampleRate in 1..0xFFFFFF) { "采样率超出范围: $sampleRate" }
        val bits = BitWriter()
        bits.write(objectType, 5)
        val index = sampleRateIndex(sampleRate)
        if (index >= 0) {
            bits.write(index, 4)
        } else {
            bits.write(ESCAPE_INDEX, 4)
            bits.write(sampleRate, 24)
        }
        bits.write(channels, 4)
        // frameLengthFlag（1024 点）、dependsOnCoreCoder、extensionFlag 都是 0。
        bits.write(0, 3)
        return bits.toByteArray()
    }

    /** 一个 ADTS 头。[frameLength] 含头本身。 */
    class Adts(
        val objectType: Int,
        val sampleRateIndex: Int,
        val channelConfig: Int,
        val frameLength: Int,
        val headerLength: Int,
    ) {
        val sampleRate: Int get() = SAMPLE_RATES[sampleRateIndex]

        /** 按这个头生成 `csd-0`。声道配置为 0（由码流里的 PCE 决定）时按双声道。 */
        fun audioSpecificConfig(): ByteArray =
            audioSpecificConfig(sampleRate, if (channelConfig == 0) 2 else channelConfig, objectType)
    }

    /** 播放线程复用的可变 ADTS 解析结果，避免每包分配 [Adts]。 */
    internal class AdtsScratch {
        var objectType: Int = OBJECT_TYPE_LC
        var sampleRateIndex: Int = 3
        var channelConfig: Int = 2
        var frameLength: Int = 0
        var headerLength: Int = 0

        val sampleRate: Int get() = SAMPLE_RATES[sampleRateIndex]

        fun audioSpecificConfig(): ByteArray =
            audioSpecificConfig(sampleRate, if (channelConfig == 0) 2 else channelConfig, objectType)
    }

    /**
     * 解析 `data[offset, offset + length)` 开头的 ADTS 头；不是 ADTS、或声明的帧长不在 [头长, length] 之内返回 null。
     */
    fun parseAdts(data: ByteArray, offset: Int, length: Int): Adts? {
        val scratch = AdtsScratch()
        if (!parseAdtsInto(data, offset, length, scratch)) return null
        return Adts(scratch.objectType, scratch.sampleRateIndex, scratch.channelConfig, scratch.frameLength, scratch.headerLength)
    }

    internal fun parseAdtsInto(data: ByteArray, offset: Int, length: Int, out: AdtsScratch): Boolean {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "范围非法" }
        if (length < ADTS_HEADER) return false
        fun u8(i: Int) = data[offset + i].toInt() and 0xFF
        if (u8(0) != 0xFF || u8(1) and 0xF6 != 0xF0) return false
        val headerLength = if (u8(1) and 0x01 != 0) ADTS_HEADER else ADTS_HEADER_CRC
        val objectType = (u8(2) ushr 6) + 1
        val sampleRateIndex = (u8(2) ushr 2) and 0x0F
        if (sampleRateIndex >= SAMPLE_RATES.size) return false
        val channelConfig = ((u8(2) and 0x01) shl 2) or (u8(3) ushr 6)
        val frameLength = ((u8(3) and 0x03) shl 11) or (u8(4) shl 3) or (u8(5) ushr 5)
        if (frameLength < headerLength || frameLength > length) return false
        out.objectType = objectType
        out.sampleRateIndex = sampleRateIndex
        out.channelConfig = channelConfig
        out.frameLength = frameLength
        out.headerLength = headerLength
        return true
    }

    private const val ESCAPE_INDEX = 15
    private const val ADTS_HEADER = 7
    private const val ADTS_HEADER_CRC = 9

    private class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var current = 0
        private var used = 0

        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                used++
                if (used == 8) {
                    bytes += current.toByte()
                    current = 0
                    used = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (used > 0) {
                bytes += (current shl (8 - used)).toByte()
                current = 0
                used = 0
            }
            return bytes.toByteArray()
        }
    }
}
