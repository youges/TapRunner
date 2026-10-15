package cn.taprunner.engine

import cn.taprunner.model.*
import cn.taprunner.model.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.random.Random

interface Runtime {
    suspend fun gesture(from: Point, to: Point?, duration: Long): Boolean
    suspend fun find(target: Target): Point?
    suspend fun key(name: String): Boolean
    suspend fun input(target: Target, text: String): Boolean
    suspend fun launch(pkg: String): Boolean
    suspend fun image(step: Step): Boolean
    fun log(stepId: String, result: String)
    fun healthy(expectedPkg: String): String?
    fun now(): Long
}
enum class Status { IDLE, RUNNING, PAUSED, FINISHED, FAULT }
enum class FaultCause { ACCESSIBILITY }
data class Progress(val status: Status = Status.IDLE, val step: String = "", val round: Int = 0, val completed: Int = 0, val gestures: Int = 0, val message: String = "就绪", val faultCause: FaultCause? = null)

object Validator {
    private val types = setOf("launchApp", "click", "longPress", "swipe", "delay", "waitText", "waitImage", "loop", "if", "key", "inputText", "log", "stop")
    fun validate(s: Script) {
        require(s.schema == 1) { "不支持的脚本版本" }; require(s.steps.isNotEmpty()) { "请先添加步骤" }
        require(s.steps.size <= 10000) { "步骤不能超过 10000" }
        require(s.steps.map { it.id }.toSet().size == s.steps.size) { "步骤 ID 重复" }
        require(s.meta.minIntervalMs in 50..60000 && s.meta.maxLoopIterations in 1..10000) { "间隔或循环上限无效" }
        require(s.meta.repeatCount in 1..s.meta.maxLoopIterations){"循环次数必须在 1 到循环上限之间"}
        val ids = s.steps.associateBy { it.id }
        fun coord(v: Double) = require(v.isFinite() && v in 0.0..1.0) { "坐标必须在 0 到 1 之间" }
        s.steps.forEach { st ->
            require(st.type in types) { "未知步骤 ${st.type}" }; require(st.id.isNotBlank()) { "步骤 ID 为空" }
            listOf(st.x, st.y).forEach(::coord); st.fallback?.let { coord(it.x); coord(it.y) }
            require(st.ms in 0..86400000 && st.timeoutMs in 1..3600000 && st.pollMs in 100..60000 && st.holdMs in 1..60000 && st.durationMs in 1..60000 && st.waitMs in 0..60000) { "步骤 ${st.id} 时长无效" }
            require(st.jitter in 0.0..0.05) { "坐标微调幅度过大" }
            st.intervalMs?.let {require(it in 50..60000){"点位间隔必须在 50 到 60000 毫秒之间"}}
            if (st.type == "swipe") { require(st.from.size == 2 && st.to.size == 2); (st.from + st.to).forEach(::coord) }
            if (st.type == "loop") { require(st.times in 1..s.meta.maxLoopIterations && st.body.isNotEmpty()) { "循环必须有界且包含步骤" } }
            if (st.type == "if") require(st.condition != null) { "条件不能为空" }
            if (st.type == "waitImage") { require(st.template.isNotBlank() && st.roi.size == 4); st.roi.forEach(::coord); require(st.roi[2] > st.roi[0] && st.roi[3] > st.roi[1]); require(st.threshold in .6..1.0) }
            if (st.type == "inputText") require(st.target != null)
            st.childStepIds().forEach { require(it in ids) { "引用的步骤 $it 不存在" } }
            st.onError?.let { e ->
                if (e is JsonPrimitive) require(e.content in setOf("abort", "skip")) { "未知失败策略" }
                else { require(e is JsonObject); e["goto"]?.let { require(it.jsonPrimitive.content in ids) { "跳转目标不存在" } }; e["retry"]?.jsonObject?.let { require((it["times"]?.jsonPrimitive?.int ?: 0) in 1..10) } }
            }
        }
        val active = mutableSetOf<String>(); val heights = mutableMapOf<String, Int>()
        fun visit(id: String, depth: Int): Int {
            require(depth <= 5) { "嵌套不能超过 5 层" }; require(id !in active) { "步骤引用形成循环" }
            heights[id]?.let { height -> require(depth + height <= 5) { "嵌套不能超过 5 层" }; return height }
            active += id
            val height = ids.getValue(id).childStepIds().maxOfOrNull { visit(it, depth + 1) + 1 } ?: 0
            active -= id; heights[id] = height
            return height
        }
        ids.keys.forEach { visit(it, 0) }
        // All goto cycles are rejected: explicit bounded loops are easier to inspect and stop.
        fun gotoVisit(id: String, path: Set<String>) {
            require(id !in path) { "goto 形成循环，请使用有限 loop" }
            (ids.getValue(id).onError as? JsonObject)?.get("goto")?.jsonPrimitive?.content?.let { gotoVisit(it, path + id) }
        }
        ids.keys.forEach { gotoVisit(it, emptySet()) }
    }
}

class Engine(private val runtime: Runtime, private val scope: CoroutineScope) {
    val state = MutableStateFlow(Progress())
    private var job: Job? = null
    private var paused = false
    private var budget = 0
    private var executions = 0
    private var script: Script? = null
    private var stepsById: Map<String,Step> = emptyMap()
    private val interpolation=Regex("\\{\\{(\\w+)\\}\\}")
    fun start(s: Script) {
        check(job?.isActive != true) { "已有脚本正在执行" }; Validator.validate(s)
        script = s; stepsById=s.steps.associateBy {it.id}; paused = false; budget = 0; executions = 0; state.value = Progress(Status.RUNNING, message = s.name)
        job = scope.launch {
            try {
                val owned = s.steps.flatMap { it.childStepIds() }.toSet()
                val rootSteps=s.steps.filter {it.id !in owned}.map {it.id}
                repeat(s.meta.repeatCount){round ->
                    checkpoint()
                    if(s.meta.repeatCount>1)require(++budget<=s.meta.maxLoopIterations){"达到循环上限"}
                    state.value=state.value.copy(round=round)
                    sequence(rootSteps,s)
                }
                state.value = state.value.copy(status = Status.FINISHED, message = "执行完成")
            } catch (e: CancellationException) { throw e }
            catch (e: Finish) { state.value = state.value.copy(status = Status.FINISHED, message = e.message ?: "完成") }
            catch (e: Exception) { runtime.log(state.value.step, "FAULT: ${e.message}"); state.value = state.value.copy(status = Status.FAULT, message = e.message ?: "执行失败") }
        }
    }
    fun pause() { if (state.value.status == Status.RUNNING) { paused = true; state.value = state.value.copy(status = Status.PAUSED, message = "已暂停") } }
    fun resume() { if (state.value.status == Status.PAUSED) { paused = false; state.value = state.value.copy(status = Status.RUNNING, message = "继续执行") } }
    fun stop(reason: String = "已停止", fault: Boolean = false, cause: FaultCause? = null) { job?.cancel(); paused = false; state.value = state.value.copy(status = if (fault) Status.FAULT else Status.FINISHED, message = reason, faultCause = cause); runtime.log(state.value.step, reason) }
    fun accessibilityUnavailable(reason: String) {
        if (state.value.status in listOf(Status.RUNNING, Status.PAUSED)) stop(reason, true, FaultCause.ACCESSIBILITY)
    }
    fun accessibilityConnected() {
        val progress = state.value
        if (progress.status == Status.FAULT && progress.faultCause == FaultCause.ACCESSIBILITY)
            state.value = progress.copy(message = "无障碍已重新连接；上次运行已停止，请手动重新开始")
        else if (progress.status == Status.IDLE) state.value = progress.copy(message = "无障碍已连接")
    }
    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        runtime.healthy(script?.meta?.expectedPkg.orEmpty())?.let { error(it) }
        while (paused) { delay(50); currentCoroutineContext().ensureActive(); runtime.healthy(script?.meta?.expectedPkg.orEmpty())?.let { error(it) } }
    }
    private suspend fun sleep(ms: Long) { var remaining = ms; while (remaining > 0) { checkpoint(); val n = minOf(remaining, 50); delay(n); remaining -= n } }
    private suspend fun sequence(ids: List<String>, s: Script) {
        var index = 0
        while (index < ids.size) {
            checkpoint(); require(++executions <= 100000) { "达到执行步数护栏" }
            val st = stepsById.getValue(ids[index]); state.value = state.value.copy(step = st.id, message = st.note.ifBlank { st.type })
            var failure: Exception? = null; var attempt = 0
            val retry = (st.onError as? JsonObject)?.get("retry") as? JsonObject
            do {
                try { execute(st, s); failure = null; break }
                catch (e: CancellationException) { throw e }
                catch (e: Finish) { throw e }
                catch (e: Exception) { failure = e; if (attempt++ >= (retry?.get("times")?.jsonPrimitive?.int ?: 0)) break; sleep(retry?.get("backoffMs")?.jsonPrimitive?.long ?: 500) }
            } while (true)
            if (failure != null) {
                checkpoint() // Lost permissions and lock screen cannot be skipped by onError.
                val go = (st.onError as? JsonObject)?.get("goto")?.jsonPrimitive?.content
                if (go != null) { require(go in ids) { "goto 不能跨序列跳转" }; index = ids.indexOf(go); continue }
                if ((st.onError as? JsonPrimitive)?.content != "skip") throw failure
                runtime.log(st.id, "SKIP: ${failure.message}")
            } else runtime.log(st.id, "OK")
            state.value = state.value.copy(completed = state.value.completed + 1); index++
        }
    }
    private fun interpolate(value: String, s: Script): String = interpolation.replace(value) { if (it.groupValues[1] == "round") state.value.round.toString() else s.vars[it.groupValues[1]] ?: it.value }
    private suspend fun execute(st: Step, s: Script) {
        when (st.type) {
            "click", "longPress" -> {
                val target = st.target
                val p = if (target != null) runtime.find(target) ?: st.fallback ?: error("目标控件不存在且未配置备用坐标") else Point(st.x, st.y)
                fun jitter(v: Double) = (v + if (st.jitter > 0) Random.nextDouble(-st.jitter, st.jitter) else 0.0).coerceIn(0.0, 1.0)
                val startedAt=runtime.now()
                check(runtime.gesture(Point(jitter(p.x), jitter(p.y)), null, if (st.type == "longPress") maxOf(500, st.holdMs) else st.holdMs)) { "手势未完成" }
                state.value = state.value.copy(gestures = state.value.gestures + 1)
                sleep(((st.intervalMs?:s.meta.minIntervalMs)-(runtime.now()-startedAt)).coerceAtLeast(0))
            }
            "swipe" -> { val startedAt=runtime.now();check(runtime.gesture(Point(st.from[0], st.from[1]), Point(st.to[0], st.to[1]), st.durationMs)) { "滑动失败" }; state.value = state.value.copy(gestures = state.value.gestures + 1); sleep(((st.intervalMs?:s.meta.minIntervalMs)-(runtime.now()-startedAt)).coerceAtLeast(0)) }
            "delay" -> sleep(st.ms)
            "waitText", "waitImage" -> {
                var elapsed = 0L
                while (true) {
                    checkpoint()
                    val hit = if (st.type == "waitText") runtime.find(st.target ?: Target("text", interpolate(st.text, s), st.mode)) != null else runtime.image(st)
                    if (hit) break
                    check(elapsed < st.timeoutMs) { "等待 ${st.text.ifBlank { st.template }} 超时" }
                    val wait = minOf(st.pollMs, st.timeoutMs - elapsed); sleep(wait); elapsed += wait
                }
            }
            "loop" -> repeat(st.times) { r -> checkpoint(); require(++budget <= s.meta.maxLoopIterations) { "达到循环上限" }; state.value = state.value.copy(round = r); sequence(st.body, s) }
            "if" -> sequence(if (runtime.find(st.condition!!) != null) st.then else st.otherwise, s)
            "key" -> check(runtime.key(st.key)) { "不支持该系统按键" }
            "inputText" -> check(runtime.input(st.target!!, interpolate(st.text, s))) { "输入文本失败" }
            "launchApp" -> { check(runtime.launch(st.pkg)) { "无法打开应用 ${st.pkg}" }; sleep(st.waitMs) }
            "log" -> runtime.log(st.id, interpolate(st.message, s))
            "stop" -> throw Finish(st.reason)
        }
    }
    private class Finish(message: String): RuntimeException(message)
}
