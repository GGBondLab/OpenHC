package io.github.gcbond.openhc.connection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import io.github.gcbond.openhc.platform.media.DisplayRefreshRate

/** 从系统读车机的蓝牙地址属性和屏幕参数。 */
internal object DeviceInfo {

    private val systemPropertiesGet: java.lang.reflect.Method? by lazy { findSystemPropertiesGet() }

    @SuppressLint("PrivateApi")
    private fun findSystemPropertiesGet(): java.lang.reflect.Method? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    @Volatile
    private var cachedBluetoothAddress: String? = null

    /**
     * 按顺序读系统属性 [BluetoothAddressPolicy.SYSTEM_PROPERTY_KEYS]，取第一个有效的地址，都读不到返回 null。
     * 系统属性的读取接口没有公开，只能反射。安卓 9 起非公开接口受限，这个接口属于允许调用、系统只记一行警告的那一类；
     * 将来的系统要是拦了，这里返回 null，退回手填地址。
     */
    fun systemBluetoothAddress(): String? {
        cachedBluetoothAddress?.let { return it }
        val get = systemPropertiesGet ?: return null
        return try {
            for (key in BluetoothAddressPolicy.SYSTEM_PROPERTY_KEYS) {
                val raw = get.invoke(null, key) as? String ?: continue
                val valid = BluetoothAddressPolicy.normalize(raw)
                if (valid != null) {
                    cachedBluetoothAddress = valid
                    return valid
                }
            }
            null
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 默认屏幕（车机主屏）的实际像素、DPI 和按刷新率算出的帧率。 */
    fun screen(context: Context): ScreenSpec {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        val size = Point()
        if (display != null) {
            // getRealSize/getRealMetrics 在安卓 12 被标为过时（新接口要界面 Context），但仍然返回整块屏幕的实际像素，
            // 应用 Context 上也能用。
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }
        if (size.x <= 0 || size.y <= 0) {
            val fallback = context.resources.displayMetrics
            size.set(fallback.widthPixels, fallback.heightPixels)
            metrics.densityDpi = fallback.densityDpi
        }
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
        return ScreenSpec(size.x, size.y, dpi, DisplayRefreshRate.declaredFps(context))
    }
}
