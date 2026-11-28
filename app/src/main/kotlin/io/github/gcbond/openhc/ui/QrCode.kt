package io.github.gcbond.openhc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.gcbond.openhc.R
import kotlin.math.floor

/** 二维码编码（ZXing，Apache-2.0）。纯逻辑，由 JVM 单元测试覆盖。 */
internal object QrCodes {
    /**
     * 编成模块矩阵：一个模块一个点，四周留 [margin] 个模块的空白（扫码要有静区）。内容按 UTF-8 编码、不做 URL 编码，
     * 纠错等级 M（车机屏幕清楚，够用，码也不至于太密）。
     */
    fun encode(content: String, margin: Int = 2): BitMatrix {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // 宽高给 0：按最小尺寸出，一个模块一个点，放大交给界面。
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }
}

/**
 * 显示二维码：白底黑块，不随深色模式反色（反色了很多手机扫不出来）。
 * 直接在画布上按每模块整数像素居中绘制，零位图分配且消除非整数倍缩放产生的宽窄交替模糊。
 */
@Composable
internal fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { QrCodes.encode(content) }
    val desc = stringResource(R.string.wireless_qr_description)
    Canvas(
        modifier = modifier
            .background(Color.White)
            .semantics { contentDescription = desc },
    ) {
        val cols = matrix.width
        val rows = matrix.height
        if (cols <= 0 || rows <= 0) return@Canvas
        val rawCell = minOf(size.width / cols, size.height / rows)
        val cell = if (rawCell >= 2f) floor(rawCell) else rawCell
        val left = floor((size.width - cell * cols) / 2f)
        val top = floor((size.height - cell * rows) / 2f)
        for (y in 0 until rows) {
            var x = 0
            while (x < cols) {
                if (matrix.get(x, y)) {
                    var run = 1
                    while (x + run < cols && matrix.get(x + run, y)) run++
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + x * cell, top + y * cell),
                        size = Size(cell * run, cell),
                    )
                    x += run
                } else {
                    x++
                }
            }
        }
    }
}
