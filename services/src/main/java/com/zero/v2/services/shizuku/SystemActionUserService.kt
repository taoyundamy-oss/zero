package com.zero.v2.services.shizuku

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.TimeUnit

/**
 * Shizuku 特权 UserService —— 跑在 Shizuku 服务进程（M1.1 骨架，方案 §1.1/§六）。
 *
 * - 全部系统动作经 spawn shell 命令（input/am/pm/settings）执行；M1.2 B9 延迟优化
 *   改 binder 直调注入（UiAutomation/InputManager，非 spawn shell），目标 <50ms/动作。
 * - 本类运行于 Shizuku 进程（非 Zero 进程）——不可使用 Hilt/AgentTrace；失败经异常
 *   回传调用方，trace 由 ShizukuBridge 侧统一埋（E28 铁律）。
 * - destroy() = Shizuku 销毁约定（AIDL 事务码 16777114）：unbindUserService(remove=true)
 *   时由 Shizuku server 调用 → System.exit 结束本进程（官方 demo 同款）。
 * - 保留无参构造（Shizuku server 反射实例化要求）。
 */
class SystemActionUserService : ISystemActionService.Stub() {

    override fun destroy() {
        // Shizuku server 销毁约定：退出 UserService 进程
        System.exit(0)
    }

    // ═══ shell 执行底座 ═══

    /**
     * 通用 shell 执行：sh -c，stdout/stderr 合流读取（单流无死锁）。
     * @return (exitCode, 输出文本)；10s 超时 destroyForcibly（防挂死——特权进程无外层超时治理）。
     */
    private fun exec(cmd: String): Pair<Int, String> = try {
        val proc = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
        if (proc.waitFor(EXEC_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.exitValue() to output
        } else {
            proc.destroyForcibly()
            -1 to "timeout after ${EXEC_TIMEOUT_S}s"
        }
    } catch (e: Exception) {
        -2 to "exec_failed: ${e.message ?: e.javaClass.simpleName}"
    }

    /** POSIX 单引号转义（值含空格/特殊字符时保护命令行）。 */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** void 动作统一出口：非 0 退出码抛异常回传（Bridge 捕获并 trace）。 */
    private fun requireOk(action: String, cmd: String) {
        val (exit, out) = exec(cmd)
        if (exit != 0) throw IllegalStateException("$action failed (exit=$exit): ${out.take(200)}")
    }

    // ═══ B9：binder 直调手势注入（InputManager.injectInputEvent，M1.2）═══
    // Shizuku 进程 uid=2000/0——Android hidden API 限制对 shell/root 豁免，反射直调可行；
    // 较 spawn `input tap`（~100-300ms/次）省进程创建开销，目标 <50ms/动作（实测超标标 G 类再迭代）。
    // 任何失败回退 exec 路径（保底可用）。耗时打点 logcat TAG=ZeroShizuku（特权进程内无 AgentTrace）。

    private val inputManagerInstance: Any? by lazy {
        try {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        } catch (t: Throwable) { null }
    }

    private val injectInputEventMethod: java.lang.reflect.Method? by lazy {
        try {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("injectInputEvent", android.view.InputEvent::class.java, Integer.TYPE)
        } catch (t: Throwable) { null }
    }

    /** 注入单个输入事件（mode=2 INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH）。 */
    private fun injectEvent(event: MotionEvent): Boolean = try {
        val im = inputManagerInstance ?: return false
        val method = injectInputEventMethod ?: return false
        method.invoke(im, event, 2) as? Boolean ?: false
    } catch (t: Throwable) { false }

    private fun MotionEvent.touchscreen(): MotionEvent = apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    private fun injectTap(x: Int, y: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
            x.toFloat(), y.toFloat(), 0).touchscreen()
        val up = MotionEvent.obtain(downTime, downTime + TAP_DURATION_MS, MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(), 0).touchscreen()
        try {
            return injectEvent(down) && injectEvent(up)
        } finally {
            down.recycle(); up.recycle()
        }
    }

    private fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val steps = maxOf(2, ms / 16)
        val events = mutableListOf<MotionEvent>()
        try {
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                x1.toFloat(), y1.toFloat(), 0).touchscreen().let { events.add(it); if (!injectEvent(it)) return false }
            for (i in 1 until steps) {
                val nx = x1 + (x2 - x1) * i / steps
                val ny = y1 + (y2 - y1) * i / steps
                val t = downTime + ms.toLong() * i / steps
                MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE,
                    nx.toFloat(), ny.toFloat(), 0).touchscreen().let { events.add(it); if (!injectEvent(it)) return false }
            }
            MotionEvent.obtain(downTime, downTime + ms, MotionEvent.ACTION_UP,
                x2.toFloat(), y2.toFloat(), 0).touchscreen().let { events.add(it); return injectEvent(it) }
        } finally {
            events.forEach { it.recycle() }
        }
    }

    // ═══ AIDL 实现 ═══

    override fun runShell(cmd: String): String {
        val (exit, out) = exec(cmd)
        return if (out.isEmpty()) "exit=$exit" else "exit=$exit\n$out"
    }

    override fun tap(x: Int, y: Int) {
        // B9：binder 直调优先，失败回退 spawn exec
        val t0 = SystemClock.elapsedRealtime()
        if (injectTap(x, y)) {
            Log.i(TAG, "tap binder ${SystemClock.elapsedRealtime() - t0}ms ($x,$y)")
            return
        }
        Log.w(TAG, "tap binder 注入失败 → exec 回退")
        requireOk("tap", "input tap $x $y")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        val t0 = SystemClock.elapsedRealtime()
        if (injectSwipe(x1, y1, x2, y2, ms)) {
            Log.i(TAG, "swipe binder ${SystemClock.elapsedRealtime() - t0}ms ($x1,$y1→$x2,$y2 ${ms}ms)")
            return
        }
        Log.w(TAG, "swipe binder 注入失败 → exec 回退")
        requireOk("swipe", "input swipe $x1 $y1 $x2 $y2 $ms")
    }

    override fun inputText(text: String) =
        // Android `input text` 约定：%s = 空格（裸空格会截断参数）
        requireOk("input_text", "input text ${q(text.replace(" ", "%s"))}")

    override fun pressKey(name: String) = requireOk("press_key", "input keyevent ${q(name)}")

    override fun startActivity(component: String, action: String, data: String): Boolean {
        val sb = StringBuilder("am start")
        if (component.isNotBlank()) sb.append(" -n ").append(q(component))
        if (action.isNotBlank()) sb.append(" -a ").append(q(action))
        if (data.isNotBlank()) sb.append(" -d ").append(q(data))
        return exec(sb.toString()).first == 0
    }

    override fun grantPermission(pkg: String, perm: String): Boolean =
        exec("pm grant ${q(pkg)} ${q(perm)}").first == 0

    override fun setSetting(namespace: String, key: String, value: String): Boolean =
        exec("settings put ${q(namespace)} ${q(key)} ${q(value)}").first == 0

    companion object {
        private const val TAG = "ZeroShizuku"
        private const val EXEC_TIMEOUT_S = 10L
        private const val TAP_DURATION_MS = 50L
    }
}
