package io.github.gcbond.openhc.startup

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.gcbond.openhc.MainActivity
import io.github.gcbond.openhc.OpenHcApp
import io.github.gcbond.openhc.platform.log.Log

/**
 * 开机广播接收器（清单里默认停用，设置「开机自动打开 OpenHC」打开时才启用）。冷启动和熄火后再点火都走这里。
 *
 * 默认只打开界面，不起服务，连接要的前台服务由界面打开后发起（[BootLaunchWatch]）。
 * 设置「开机静默连接」开着时不打开界面，在后台起前台服务等待（[SilentBootWatch]），后台开始不了再改走打开界面。
 */
class BootStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BootStartPolicy.isBootBroadcast(intent.action)) return
        val now = SystemClock.elapsedRealtime()
        val duplicate = synchronized(DEDUPE_LOCK) {
            if (BootStartPolicy.isDuplicateBoot(lastHandledElapsedMs, now)) {
                true
            } else {
                lastHandledElapsedMs = now
                false
            }
        }
        if (duplicate) {
            Log.i(BootStart.TAG, "收到重复开机广播（${intent.action}），跳过")
            return
        }
        val app = context.applicationContext
        // 熄火不一定结束进程：点火时也补一次「暂停车机 WiFi 扫描」的恢复、自动打开的车机热点的关闭。
        (app as? OpenHcApp)?.recoverAdbSwitchesIfNeeded("开机或点火")
        if (app is OpenHcApp && BootStart.isSilent(app)) {
            SilentBootWatch(app, goAsync()).start()
        } else {
            BootLaunchWatch(app, goAsync()).start()
        }
    }

    private companion object {
        val DEDUPE_LOCK = Any()
        var lastHandledElapsedMs = -1L
    }
}

/**
 * 打开界面并看它在不在前台：[BootStartPolicy.CHECK_DELAY_MS] 后不在（没出来，或者出来后被车机桌面盖住了）
 * 就再次拉到前台，最多补开 [BootStartPolicy.MAX_COVERED_RETRIES] 次。全在主线程上。
 *
 * 开机广播超过约 10 秒会被系统判超时，因此界面一到前台或第一次补开后即放手 [pending]，后续重拉在进程内完成。
 */
internal class BootLaunchWatch(
    private val app: Context,
    private val pending: BroadcastReceiver.PendingResult?,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val application = app as? Application
    private val atMillis = System.currentTimeMillis()
    private val sinceBootMillis = SystemClock.elapsedRealtime()
    private val installStamp = BootStart.installedAtMillis(app)
    private var inFront = false
    private var everShown = false
    private var retries = 0
    private var released = false
    private var done = false

    fun start() {
        val kind = if (BootStartPolicy.isFreshBoot(sinceBootMillis)) "系统刚启动" else "系统已运行 ${sinceBootMillis / 60_000} 分钟"
        val overlay = BootStart.canDrawOverlays(app)
        Log.i(BootStart.TAG, "收到开机广播（$kind），打开界面；悬浮窗权限：${if (overlay) "有" else "没有"}，安卓 API ${Build.VERSION.SDK_INT}")
        // 先记一笔：进程之后被收掉的话，设置页至少知道开机广播送到了。
        record(BootOutcome.PENDING)
        application?.registerActivityLifecycleCallbacks(this)
        if (!open(resurfaceOnly = false)) {
            finish(BootOutcome.FAILED)
            return
        }
        handler.postDelayed(::check, BootStartPolicy.CHECK_DELAY_MS)
    }

    private fun open(resurfaceOnly: Boolean): Boolean = try {
        val intent = if (resurfaceOnly) BootStart.surfaceIntent(app) else BootStart.launchIntent(app)
        app.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        Log.e(BootStart.TAG, "打开界面失败", e)
        false
    }

    private fun check() {
        if (done) return
        if (BootStartPolicy.shouldRetryAttempt(inFront, retries)) {
            retries++
            Log.w(BootStart.TAG, "界面不在前台（${if (everShown) "打开过、被盖住了" else "没出来"}），第 $retries 次补开")
            open(resurfaceOnly = everShown)
            // 先放行系统广播，避免多次补开累计超过 BroadcastReceiver 超时阈值。
            releaseBroadcast()
            handler.postDelayed(::check, BootStartPolicy.CHECK_DELAY_MS)
            return
        }
        finish(BootStartPolicy.outcome(inFront, everShown, Build.VERSION.SDK_INT, BootStart.canDrawOverlays(app)))
    }

    private fun finish(outcome: BootOutcome) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        application?.unregisterActivityLifecycleCallbacks(this)
        record(outcome)
        Log.i(BootStart.TAG, "开机打开界面的结果：$outcome")
        releaseBroadcast()
    }

    private fun releaseBroadcast() {
        if (released) return
        released = true
        pending?.finish()
    }

    private fun record(outcome: BootOutcome) {
        try {
            BootStart.saveRecord(app, BootRecord(atMillis, sinceBootMillis, outcome, installStamp))
        } catch (e: RuntimeException) {
            Log.w(BootStart.TAG, "记录开机结果失败：${e.message}")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        inFront = true
        everShown = true
        releaseBroadcast()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is MainActivity) inFront = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
