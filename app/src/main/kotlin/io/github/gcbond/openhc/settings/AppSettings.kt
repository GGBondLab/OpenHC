package io.github.gcbond.openhc.settings

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.core.content.edit
import io.github.gcbond.openhc.connection.DisplayPlan

/**
 * 应用设置（SharedPreferences）。连接相关的几项在下一次连接开始时读。任意线程可读写。
 */
@Stable
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 声明给手机的帧率；0 表示按屏幕刷新率。 */
    var fps: Int
        get() = prefs.getInt(KEY_FPS, 0).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_FPS, value.coerceAtLeast(0)) }

    /** 「界面大小」：声明给手机的 DPI 按这个百分比缩小，只认设置页列出的档位，别的值按默认。下次连接生效。 */
    var uiScalePercent: Int
        get() = prefs.getInt(KEY_UI_SCALE, DisplayPlan.DEFAULT_UI_SCALE).takeIf { it in DisplayPlan.UI_SCALE_CHOICES }
            ?: DisplayPlan.DEFAULT_UI_SCALE
        set(value) = prefs.edit { putInt(KEY_UI_SCALE, value) }

    /** 用户手填的车机蓝牙地址（原样保存，用的时候再校验）；没填为 null。 */
    var manualBluetoothAddress: String?
        get() = prefs.getString(KEY_MANUAL_BT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_MANUAL_BT) else putString(KEY_MANUAL_BT, value.trim())
        }

    /**
     * 应用被「设备接入」拉起过：用户在系统框里同意过用本应用打开手机（勾了「默认」之后系统会自动授权）。
     * 这之后（「插线时不再弹窗」开着时）有线会话先等一会儿看系统会不会自动授权，再自己弹授权框，免掉一个多余的框。
     */
    var usbAttachAccepted: Boolean
        get() = prefs.getBoolean(KEY_USB_ATTACH_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_USB_ATTACH_ACCEPTED, value) }

    /**
     * 「全屏显示」：开（默认）时投屏藏起状态栏和底部导航栏、铺满整屏；关时保留底部导航栏，画面让出它的位置，
     * 声明给手机的尺寸也随之变小。下次连接生效。
     */
    var fullScreen: Boolean
        get() = prefs.getBoolean(KEY_FULL_SCREEN, true)
        set(value) = prefs.edit { putBoolean(KEY_FULL_SCREEN, value) }

    /** 界面主题模式：自动跟随车机（默认）、浅色、深色。 */
    var themeMode: Int
        get() {
            if (prefs.contains(KEY_THEME_MODE)) {
                val mode = prefs.getInt(KEY_THEME_MODE, THEME_AUTO)
                if (mode in THEME_AUTO..THEME_DARK) return mode
            }
            if (prefs.contains(KEY_DARK_THEME)) {
                return if (prefs.getBoolean(KEY_DARK_THEME, true)) THEME_DARK else THEME_LIGHT
            }
            return THEME_AUTO
        }
        set(value) = prefs.edit { putInt(KEY_THEME_MODE, value.coerceIn(THEME_AUTO, THEME_DARK)) }

    /** 按当前主题模式和车机系统的昼夜状态（大灯/夜间模式）算出此刻该用深色还是浅色。 */
    fun resolveDark(context: Context): Boolean = when (themeMode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 兼容旧字段：直接读写深浅色时同步更新 [themeMode]。 */
    var darkTheme: Boolean
        get() = themeMode != THEME_LIGHT
        set(value) {
            themeMode = if (value) THEME_DARK else THEME_LIGHT
            prefs.edit { putBoolean(KEY_DARK_THEME, value) }
        }

    /** 安卓 13 起的通知权限问过一次了（拒绝了也不再追问）。 */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_ASKED, value) }

    companion object {
        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val FILE = "openhc-settings"
        private const val KEY_FPS = "fps"
        private const val KEY_UI_SCALE = "ui_scale_percent"
        private const val KEY_MANUAL_BT = "manual_bluetooth_address"
        private const val KEY_USB_ATTACH_ACCEPTED = "usb_attach_accepted"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
        private const val KEY_FULL_SCREEN = "full_screen"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
