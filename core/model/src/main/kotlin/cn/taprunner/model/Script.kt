package cn.taprunner.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable data class Target(val find: String = "text", val value: String = "", val mode: String = "contains", val threshold: Double = .85)
@Serializable data class Point(val x: Double = .5, val y: Double = .5)
@Serializable data class Meta(val orientation: String = "any", val captureDisplay: String = "", val minIntervalMs: Long = 150, val maxLoopIterations: Int = 10000, val expectedPkg: String = "", val repeatCount:Int=1)
@Serializable data class Step(
    val id: String, val type: String = "click", val note: String = "",
    val x: Double = .5, val y: Double = .5, val holdMs: Long = 50,
    val from: List<Double> = listOf(.5, .75), val to: List<Double> = listOf(.5, .25), val durationMs: Long = 350,
    val target: Target? = null, val fallback: Point? = null, val text: String = "", val mode: String = "contains",
    val ms: Long = 500, val timeoutMs: Long = 8000, val pollMs: Long = 400,
    val times: Int = 1, val body: List<String> = emptyList(), val condition: Target? = null,
    val then: List<String> = emptyList(), @SerialName("else") val otherwise: List<String> = emptyList(),
    val key: String = "back", val pkg: String = "", val waitMs: Long = 1500,
    val message: String = "", val reason: String = "完成", val template: String = "",
    val roi: List<Double> = listOf(0.0, 0.0, 1.0, 1.0), val threshold: Double = .85,
    val onError: JsonElement? = null, val jitter: Double = 0.0, val intervalMs:Long?=null
)
@Serializable data class Script(val schema: Int = 1, val id: String, val name: String = "新脚本", val meta: Meta = Meta(), val vars: Map<String, String> = emptyMap(), val steps: List<Step> = emptyList())
object ScriptJson { val format = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true } }
