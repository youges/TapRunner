package cn.taprunner

import android.app.Application
import android.content.Context
import android.util.Log
import cn.taprunner.engine.*
import cn.taprunner.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

class TapApp: Application() {
    lateinit var store: ScriptStore
    lateinit var runtime: AndroidRuntime
    lateinit var engine: Engine
    lateinit var overlayDraft: OverlayDraft
    lateinit var appearance: AppearanceStore
    lateinit var displayProgress: StateFlow<Progress>
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate();instance=this;appearance=AppearanceStore(this);store=ScriptStore(this);overlayDraft=OverlayDraft(store);runtime=AndroidRuntime(this);engine=Engine(runtime,scope)
        displayProgress=engine.state.forDisplay(runtime::now).stateIn(scope,SharingStarted.Eagerly,engine.state.value)
        scope.launch {store.selected.collect {getSharedPreferences("library",MODE_PRIVATE).edit {putString("selected",it)}}}
    }
    companion object { lateinit var instance: TapApp }
}
class ScriptStore(private val context: Context) {
    private val dir = File(context.filesDir, "scripts").apply { mkdirs() }
    val scripts = MutableStateFlow<List<Script>>(emptyList())
    val selected = MutableStateFlow(context.getSharedPreferences("library",Context.MODE_PRIVATE).getString("selected","").orEmpty())
    private data class LogEntry(val time:Long,val step:String,val result:String)
    private val logQueue=Channel<LogEntry>(Channel.UNLIMITED)
    private val logScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    init {
        reload()
        logScope.launch {
            val logs=File(context.filesDir,"logs").apply {mkdirs()}
            var cleanedOn=""
            for(entry in logQueue)runCatching {
                val instant=java.time.Instant.ofEpochMilli(entry.time)
                val date=instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                val json=kotlinx.serialization.json.buildJsonObject {put("t",kotlinx.serialization.json.JsonPrimitive(instant.toString()));put("stepId",kotlinx.serialization.json.JsonPrimitive(entry.step));put("outcome",kotlinx.serialization.json.JsonPrimitive(entry.result))}
                File(logs,"$date.jsonl").appendText("$json\n")
                if(cleanedOn!=date){logs.listFiles().orEmpty().filter {it.lastModified()<entry.time-14L*86400000}.forEach {it.delete()};cleanedOn=date}
            }.onFailure {Log.e("TapRunner","本地日志写入失败",it)}
        }
    }
    @Synchronized fun reload() { scripts.value = dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { runCatching { ScriptJson.format.decodeFromString<Script>(it.readText()) }.getOrNull() }.sortedBy { it.name }; if (scripts.value.none { it.id == selected.value }) selected.value = scripts.value.firstOrNull()?.id.orEmpty() }
    @Synchronized fun save(script: Script, select:Boolean=true) {
        require(script.meta.minIntervalMs in 50..60000 && script.meta.maxLoopIterations in 1..10000) { "间隔或循环上限无效" }
        require(script.meta.repeatCount in 1..script.meta.maxLoopIterations){"循环次数无效"}
        if (script.steps.isNotEmpty()) Validator.validate(script)
        require(script.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "脚本 ID 无效" }
        val target = File(dir, "${script.id}.json")
        val temp = File(dir, "${script.id}.tmp"); temp.writeText(ScriptJson.format.encodeToString(script)); java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        scripts.value=(scripts.value.filter {it.id!=script.id}+script).sortedBy {it.name}
        if(select)selected.value = script.id
    }
    fun create(): Script = Script(id = UUID.randomUUID().toString()).also {save(it)}
    @Synchronized fun rename(id: String, name: String) {
        val script = scripts.value.firstOrNull { it.id == id } ?: error("脚本不存在")
        save(ScriptLibrary.rename(script, name),select=false)
    }
    @Synchronized fun duplicate(id: String): Script {
        val script = scripts.value.firstOrNull { it.id == id } ?: error("脚本不存在")
        val copy = ScriptLibrary.duplicate(script, scripts.value)
        save(copy,select=false)
        return copy
    }
    fun current() = scripts.value.firstOrNull { it.id == selected.value }
    @Synchronized fun delete(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))); File(dir, "$id.json").delete(); scripts.value=scripts.value.filter {it.id!=id};if(selected.value==id)selected.value=scripts.value.firstOrNull()?.id.orEmpty() }
    fun append(step: Step) { current()?.let { save(it.copy(steps = it.steps + step)) } }
    fun log(step: String, result: String) {
        Log.i("TapRunner", "$step $result")
        logQueue.trySend(LogEntry(System.currentTimeMillis(),step,result))
    }
    fun logs() = File(context.filesDir, "logs").listFiles().orEmpty().sortedByDescending { it.name }.take(2).joinToString("\n") { it.readText().takeLast(40000) }
}
