package io.github.gcbond.openhc.platform.media

import android.view.MotionEvent
import io.github.gcbond.openhc.core.hisight.HiSightInput

/**
 * 把一个 [MotionEvent]（坐标相对于接收触摸的视图）交给 [TouchMapper]，返回要依次发给手机的触摸事件。
 *
 * MOVE 事件里批量带来的历史采样点逐个送进去，手机端拿到的轨迹和采样率与本机一致（快速滑动时惯性更准）；
 * 位置没变的采样点不会重复发送。其他动作（悬停、滚轮等）不处理。
 */
fun TouchMapper.onMotionEvent(event: MotionEvent): List<HiSightInput.Touch> {
    val action = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> TouchMapper.Action.DOWN
        MotionEvent.ACTION_POINTER_DOWN -> TouchMapper.Action.POINTER_DOWN
        MotionEvent.ACTION_MOVE -> TouchMapper.Action.MOVE
        MotionEvent.ACTION_POINTER_UP -> TouchMapper.Action.POINTER_UP
        MotionEvent.ACTION_UP -> TouchMapper.Action.UP
        MotionEvent.ACTION_CANCEL -> TouchMapper.Action.CANCEL
        else -> return emptyList()
    }
    val count = event.pointerCount
    if (scratchIds.size < count) {
        scratchIds = IntArray(count)
        scratchXs = FloatArray(count)
        scratchYs = FloatArray(count)
    }
    val ids = scratchIds
    val xs = scratchXs
    val ys = scratchYs
    for (i in 0 until count) ids[i] = event.getPointerId(i)
    val historySize = event.historySize
    if (action == TouchMapper.Action.MOVE && historySize > 0) {
        var out: ArrayList<HiSightInput.Touch>? = null
        for (h in 0 until historySize) {
            for (i in 0 until count) {
                xs[i] = event.getHistoricalX(i, h)
                ys[i] = event.getHistoricalY(i, h)
            }
            val step = onEvent(action, -1, count, ids, xs, ys)
            if (step.isNotEmpty()) (out ?: ArrayList<HiSightInput.Touch>(4).also { out = it }).addAll(step)
        }
        for (i in 0 until count) {
            xs[i] = event.getX(i)
            ys[i] = event.getY(i)
        }
        val last = onEvent(action, -1, count, ids, xs, ys)
        if (out == null) return last
        if (last.isNotEmpty()) out!!.addAll(last)
        return out!!
    }
    for (i in 0 until count) {
        xs[i] = event.getX(i)
        ys[i] = event.getY(i)
    }
    return onEvent(action, event.getPointerId(event.actionIndex), count, ids, xs, ys)
}
