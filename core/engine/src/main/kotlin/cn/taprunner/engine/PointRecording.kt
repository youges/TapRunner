package cn.taprunner.engine

import cn.taprunner.model.Script
import cn.taprunner.model.Step
import java.util.UUID

/** Old editors can leave branch fields behind when changing a step's type. */
internal fun Step.childStepIds(): List<String> = when (type) {
    "loop" -> body
    "if" -> then + otherwise
    else -> emptyList()
}

/** Adds a recorded gesture without changing existing control flow or script metadata. */
object PointRecording {
    private val gestureTypes = setOf("click", "longPress", "swipe")
    fun append(script: Script, step: Step, previousStepId: String? = null, elapsedMs: Long? = null): Script {
        require(step.type in gestureTypes) { "录入只能添加点击、长按或滑动步骤" }
        require(script.steps.none { it.id == step.id }) { "录入步骤 ID 重复" }
        val owned = script.steps.flatMap { it.childStepIds() }.toSet()
        val roots = script.steps.filter { it.id !in owned }
        require(roots.dropLast(1).none { it.type == "stop" }) { "主序列中途含停止步骤，请移到末尾后再录入" }
        val steps = script.steps.toMutableList()
        if (previousStepId != null) {
            val previousIndex = steps.indexOfFirst { it.id == previousStepId }
            require(previousIndex >= 0) { "上一个录入点不存在，请重新开始录入" }
            val previous = steps[previousIndex]
            require(previous.type in gestureTypes && previous.id !in owned) { "上一个录入动作必须是主序列手势步骤" }
            if (elapsedMs != null) {
                // A gesture interval has a 60 second cap. Preserve longer waits separately.
                val remainder = if (elapsedMs > 60000) elapsedMs - 60000 else 0L
                require(remainder <= 86400000) { "录入等待不能超过 24 小时加 60 秒" }
                steps[previousIndex] = previous.copy(intervalMs = elapsedMs.coerceIn(50, 60000))
                if (remainder > 0) {
                    val ids = steps.map { it.id }.toSet() + step.id
                    var delayId: String
                    do { delayId = UUID.randomUUID().toString() } while (delayId in ids)
                    steps.add(previousIndex + 1, Step(id = delayId, type = "delay", ms = remainder))
                }
            }
        } else require(elapsedMs == null) { "录入间隔需要上一个录入点" }
        val lastRoot = steps.lastOrNull { it.id !in owned }
        val insertion = if (lastRoot?.type == "stop") steps.indexOfLast { it.id == lastRoot.id } else steps.size
        steps.add(insertion, step)
        return script.copy(steps = steps).also(Validator::validate)
    }
}
