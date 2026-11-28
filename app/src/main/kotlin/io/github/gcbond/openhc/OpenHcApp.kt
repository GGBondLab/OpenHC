package io.github.gcbond.openhc

import android.app.Application
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.diagnostics.LogRecorder
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.ProjectionSurfacer
import io.github.gcbond.openhc.wireless.LePairingNames
import io.github.gcbond.openhc.wifi.CarHotspotAuto
import io.github.gcbond.openhc.wifi.ScanPause
import io.github.gcbond.openhc.wifi.createCarHotspotAuto
import io.github.gcbond.openhc.wifi.createScanPause
import io.github.gcbond.openhc.wireless.WirelessController

class OpenHcApp : Application() {

    private val connectionLazy = lazy {
        ConnectionController(this).also { controller ->
            // 有线开始时无线让出等回连，有线结束后无线再回到等待。
            controller.addHostListener {
                wirelessIfCreated?.onWiredChanged()
                if (BootStart.SILENT_AVAILABLE) surfacer.onStateChanged()
            }
        }
    }

    /** 有线投屏的总调度，进程内唯一。 */
    val connection: ConnectionController by connectionLazy

    internal val connectionIfCreated: ConnectionController? get() = if (connectionLazy.isInitialized()) connection else null

    private val wirelessLazy = lazy {
        WirelessController(
            this,
            wiredActive = { connectionIfCreated?.state?.value?.active == true },
            // 有线在准备或等插线、车机上又没插着手机：只是在等插线，认识的手机蓝牙连上时让给无线。
            wiredWaitingForPhone = {
                connectionIfCreated?.state?.value?.phase in WIRED_WAITING_PHASES && !BootStart.phonePlugged(this)
            },
            stopWiredWaiting = { connectionIfCreated?.stop() },
            resumeWiredWaiting = { connection.start() },
            phoneOnCable = { BootStart.phonePlugged(this) },
        ).also { controller ->
            if (BootStart.SILENT_AVAILABLE) controller.addHostListener { surfacer.onStateChanged() }
        }
    }

    /** 无线投屏的总调度，进程内唯一；和有线互斥（有线在进行时不开始）。 */
    val wireless: WirelessController by wirelessLazy

    /** 无线调度已经建了才返回它：只想看一眼状态时不为此去建。 */
    internal val wirelessIfCreated: WirelessController? get() = if (wirelessLazy.isInitialized()) wireless else null

    private val scanPauseLazy = lazy { createScanPause(this) }
    private val carHotspotAutoLazy = lazy { createCarHotspotAuto(this) }

    /** 「连接时暂停车机 WiFi 扫描」（可选，默认关）。 */
    internal val scanPause: ScanPause by scanPauseLazy
    internal val scanPauseIfCreated: ScanPause? get() = if (scanPauseLazy.isInitialized()) scanPause else null

    /** 「自动打开车机热点」（可选，默认关）。 */
    internal val carHotspotAuto: CarHotspotAuto by carHotspotAutoLazy
    internal val carHotspotAutoIfCreated: CarHotspotAuto? get() = if (carHotspotAutoLazy.isInitialized()) carHotspotAuto else null

    /** 本应用有没有界面在前台。 */
    internal val uiVisibility = UiVisibility()

    /** 开机静默连接：手机连上时把界面带到前台（静默关着时什么都不做）。 */
    private val surfacer by lazy { ProjectionSurfacer(this) }

    /** 只有确实已创建或磁盘上留有待恢复记号时才触发恢复，避免无谓创建两条 ADB 后台线程。 */
    internal fun recoverAdbSwitchesIfNeeded(why: String) {
        if (scanPauseLazy.isInitialized() || io.github.gcbond.openhc.wifi.ScanPausePreferences(this).pending) {
            scanPause.recover(why)
        }
        if (carHotspotAutoLazy.isInitialized() || io.github.gcbond.openhc.wifi.CarHotspotAutoPreferences(this).pending) {
            carHotspotAuto.recover(why)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 一启动就开始记自己的日志（「设置 → 诊断 → 导出诊断日志」用），越早越能把启动过程记全。
        LogRecorder.start(this)
        registerActivityLifecycleCallbacks(uiVisibility)
        // 启动恢复检查、身份参数缓存预读与曲线预计算全部挪到后台守护线程，不阻塞主线程冷启动首帧。
        Thread({
            try {
                // 「补全手机蓝牙配对」上次没改回去的车机蓝牙名称（进程中途没了）：现在改回去，蓝牙关着就等它打开。
                LePairingNames.restoreIfNeeded(this, "应用启动")
                recoverAdbSwitchesIfNeeded("应用启动")
                IdentityLoader.load(this)
                io.github.gcbond.openhc.core.auth.KotlinAccessoryAuthenticator.warmUp()
            } catch (_: Throwable) {
                // 预热或恢复异常不影响后续连接。
            }
        }, "OpenHC-StartupWarmUp").apply { isDaemon = true }.start()
    }

    private companion object {
        /** 有线还没接上手机的阶段：准备中、等插线。 */
        val WIRED_WAITING_PHASES = setOf(Phase.PREPARING, Phase.WAITING_FOR_PHONE)
    }
}
