package io.github.gcbond.openhc.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// 克制、干净的现代车机配色（参考 HarmonyOS 车机 / Apple CarPlay 极简深色石墨与浅色纯白风格）：
// 去掉浮夸霓虹蓝底与高对比白框，只靠中性灰阶层次拉开主次，品牌蓝仅用在核心操作与选中状态上。

/**
 * 配色。各颜色按 [dark] 取深色或浅色那一套；[dark] 是快照状态，切换后读过它的界面都会重画，
 * 所以只能在组合、布局、绘制时读，不要存进 remember 或顶层常量。
 */
internal object Palette {
    /** 当前是不是深色。由 [MainActivity][io.github.gcbond.openhc.MainActivity] 按设置和车机夜间模式写入，[ThemeToggle] 切换。 */
    var dark by mutableStateOf(true)

    /** 当前选中的主题档位（0 自动跟随车机、1 浅色、2 深色）。 */
    var themeMode by mutableStateOf(0)

    private fun pick(dark: Long, light: Long) = Color(if (this.dark) dark else light)

    /** 页面底色：中性哑光石墨黑 / 极浅中性灰。 */
    val Background get() = pick(0xFF0C0D10, 0xFFF2F3F7)

    /** 设置页全高左侧导航栏底色。 */
    val RailBackground get() = pick(0xFF12141A, 0xFFFFFFFF)

    /** 主卡片、分组框。 */
    val Card get() = pick(0xFF181B22, 0xFFFFFFFF)

    /** 卡片内次级面板（子信息块、未选中选项卡）。 */
    val CardElevated get() = pick(0xFF20242D, 0xFFF6F7FA)

    /** 极轻弱的发丝分隔描边，不抢视觉。 */
    val CardBorder get() = if (dark) Color(0x0FFFFFFF) else Color(0x120F172A)

    val Text get() = pick(0xFFF3F4F6, 0xFF111318)

    /** 比正文淡一档、仍清晰可读的说明文字。 */
    val Text2 get() = pick(0xFFA1A7B3, 0xFF4F5665)

    /** 次要文字：摘要、提示、页脚。 */
    val Muted get() = pick(0xFF6F7685, 0xFF838A98)

    /** 品牌强调蓝（克制使用：主操作、开关开启、当前图标高亮）。 */
    val Accent get() = pick(0xFF0A84FF, 0xFF007AFF)

    /** 轻柔蓝底（仅用于极少数强调图标或选中面板，不再满屏泛滥）。 */
    val Tint get() = if (dark) Color(0x1F0A84FF) else Color(0x14007AFF)
    val TintBorder get() = if (dark) Color(0x400A84FF) else Color(0x33007AFF)

    /** 要用户注意的：缺东西、出错、需要重开才生效。 */
    val Warn get() = pick(0xFFFFB340, 0xFFB86B00)
    val WarnBackground get() = if (dark) Color(0x18FFB340) else Color(0xFFFFF7E6)
    val WarnBorder get() = if (dark) Color(0x45FFB340) else Color(0x45D9820B)

    /** 好的状态（开着、已连上、投屏中）。 */
    val Positive get() = pick(0xFF30D158, 0xFF18A957)
    val PositiveBackground get() = if (dark) Color(0x1C30D158) else Color(0x1618A957)
    val PositiveBorder get() = if (dark) Color(0x3830D158) else Color(0x3318A957)

    /** 主按钮与开关开启态。 */
    val On get() = pick(0xFF0A84FF, 0xFF007AFF)
    val OnAccent get() = Color.White
    val SwitchOff get() = pick(0xFF343844, 0xFFD4D8E0)

    /** 次要按钮（「设置」「返回」「停止」、行内小按钮）。 */
    val Button get() = pick(0xFF232732, 0xFFE9ECF2)
    val ButtonBorder get() = if (dark) Color(0x0DFFFFFF) else Color(0x0F0F172A)
    val ButtonText get() = pick(0xFFF3F4F6, 0xFF111318)

    /** 分段选择器底槽与中性高亮滑块（避免设置页满屏刺眼蓝块）。 */
    val SegmentBackground get() = pick(0xFF101218, 0xFFE8EBF1)
    val SegmentSelected get() = pick(0xFF2B303C, 0xFFFFFFFF)
    val SegmentSelectedBorder get() = if (dark) Color(0x14FFFFFF) else Color(0x140F172A)

    /** 行间发丝分隔线。 */
    val Divider get() = if (dark) Color(0x0FFFFFFF) else Color(0x120F172A)

    /** 连接码方框。 */
    val PinBox get() = pick(0xFF20242D, 0xFFF3F5F8)
    val PinText get() = pick(0xFFF8FAFC, 0xFF111318)

    /** 弹出框底色。 */
    val Raised get() = pick(0xFF1B1E26, 0xFFFFFFFF)

    /** 设置页左侧导航栏选中项：干净的中性高光胶囊。 */
    val NavSelected get() = pick(0xFF222631, 0xFFEDF0F5)
    val NavSelectedText get() = pick(0xFFF5F6F8, 0xFF111318)

    /** 太阳、月亮选中时的颜色。 */
    val Sun get() = Color(0xFFF59E0B)
    val Moon get() = Color(0xFFFBBF24)

    /** 遥控器 / 方向键焦点高光框（触屏模式下不触发，仅非触屏焦点导航时显示）。 */
    val FocusRing get() = if (dark) Color(0xFFF8FAFC) else Color(0xFF007AFF)
}

/** 干净的哑光座舱底色，不做花哨渐变。 */
internal fun Modifier.cockpitBackground(): Modifier = background(Palette.Background)

/** 公共尺寸。 */
internal object Dimens {
    /** 页面左右留白；窄屏（宽不到 [WIDE_PAGE_MIN]）收成 [PageSideNarrow]。 */
    val PageSide = 28.dp
    val PageSideNarrow = 16.dp
    val WIDE_PAGE_MIN = 600.dp

    /** 低于这个高度算矮屏：字号、间距收一档。 */
    val CompactHeight = 560.dp
    val CardRadius = 18.dp
    val SubCardRadius = 14.dp
    val CardPadding = 22.dp
    val CardPaddingCompact = 16.dp
}

/** 当前是不是矮屏（见 [Dimens.CompactHeight]），由各页面的根布局按实际高度提供。 */
internal val LocalCompact = compositionLocalOf { false }

/** 各处文字样式。只定字号、粗细，颜色由调用处给；字距一律为 0（中文加字距反而难看）。 */
internal object UiType {
    private val base = TextStyle(letterSpacing = 0.sp)

    fun title(compact: Boolean) = base.copy(fontSize = if (compact) 20.sp else 23.sp, fontWeight = FontWeight.Bold)
    val topButton = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val pill = base.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    fun action(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Medium)

    // 首页卡片
    fun cardTitle(compact: Boolean) = base.copy(fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold)
    fun cardText(compact: Boolean) = base.copy(fontSize = if (compact) 14.sp else 15.sp, lineHeight = 1.5.em)
    fun cardHeader(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold)
    val status = base.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val hint = base.copy(fontSize = 13.sp, lineHeight = 1.5.em)
    fun message(compact: Boolean) = base.copy(fontSize = if (compact) 17.sp else 19.sp, lineHeight = 1.5.em)
    fun footer(compact: Boolean) = base.copy(fontSize = if (compact) 12.sp else 13.sp, lineHeight = 1.4.em)

    // 设置页
    fun groupTitle(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold)
    fun nav(compact: Boolean) = base.copy(fontSize = if (compact) 15.sp else 16.sp)
    fun rowTitle(compact: Boolean) = base.copy(fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.Medium)
    fun rowSummary(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 13.5.sp, lineHeight = 1.45.em)
    fun rowDesc(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 13.5.sp, lineHeight = 1.5.em)
    fun rowNote(compact: Boolean) =
        base.copy(fontSize = if (compact) 13.sp else 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.4.em)
    fun option(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp)
    fun value(compact: Boolean) = base.copy(fontSize = if (compact) 13.sp else 14.sp, fontFamily = FontFamily.Monospace)
    fun chevron(compact: Boolean) = base.copy(fontSize = if (compact) 18.sp else 20.sp)
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F2B3D),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF0A84FF),
    onSecondary = Color.White,
    tertiary = Color(0xFF30D158),
    background = Color(0xFF0C0D10),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF181B22),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF232732),
    onSurfaceVariant = Color(0xFFA1A7B3),
    surfaceContainerLowest = Color(0xFF0C0D10),
    surfaceContainerLow = Color(0xFF12141A),
    surfaceContainer = Color(0xFF181B22),
    surfaceContainerHigh = Color(0xFF20242D),
    surfaceContainerHighest = Color(0xFF2B303C),
    outline = Color(0xFF6F7685),
    outlineVariant = Color(0xFF343844),
    error = Color(0xFFFFB340),
    onError = Color(0xFF0C0D10),
    errorContainer = Color(0xFFFFB340),
    onErrorContainer = Color(0xFF0C0D10),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = Color(0xFF0056B3),
    secondary = Color(0xFF007AFF),
    onSecondary = Color.White,
    tertiary = Color(0xFF18A957),
    background = Color(0xFFF2F3F7),
    onBackground = Color(0xFF111318),
    surface = Color.White,
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFE9ECF2),
    onSurfaceVariant = Color(0xFF4F5665),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF6F7FA),
    surfaceContainerHighest = Color(0xFFE9ECF2),
    outline = Color(0xFF838A98),
    outlineVariant = Color(0xFFD4D8E0),
    error = Color(0xFFB86B00),
    onError = Color.White,
    errorContainer = Color(0xFFFFF7E6),
    onErrorContainer = Color(0xFFB86B00),
)

private val OpenHcTypography: Typography = Typography().let { d ->
    Typography(
        bodyLarge = d.bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = d.bodyMedium.copy(letterSpacing = 0.sp),
        bodySmall = d.bodySmall.copy(letterSpacing = 0.sp),
        labelLarge = d.labelLarge.copy(letterSpacing = 0.sp),
        labelMedium = d.labelMedium.copy(letterSpacing = 0.sp),
        labelSmall = d.labelSmall.copy(letterSpacing = 0.sp),
        titleMedium = d.titleMedium.copy(letterSpacing = 0.sp),
        titleSmall = d.titleSmall.copy(letterSpacing = 0.sp),
    )
}

/**
 * 全应用的主题：按 [Palette.dark] 用深色石墨或浅色纯白。
 */
@Composable
internal fun OpenHcTheme(content: @Composable () -> Unit) {
    val colors = if (Palette.dark) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colors, typography = OpenHcTypography, content = content)
}
