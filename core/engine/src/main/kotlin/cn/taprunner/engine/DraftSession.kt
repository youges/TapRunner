package cn.taprunner.engine

import cn.taprunner.model.Script
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class DraftSnapshot(val original:Script?,val script:Script) {
    val dirty:Boolean get()=original!=script
}

/** Changes remain in a preview buffer until an explicit commit. */
open class DraftSession(
    private val readScripts:()->List<Script>,
    private val readSelected:()->Script?,
    private val selectSaved:(String)->Unit,
    private val writeSaved:(Script)->Unit
) {
    val state=MutableStateFlow<DraftSnapshot?>(null)
    val dirty:Boolean get()=state.value?.dirty==true
    fun current():Script?=state.value?.script?:readSelected()
    fun begin():Script {
        current()?.let {script -> if(state.value==null)state.value=DraftSnapshot(script,script);return script}
        return create()
    }
    fun create():Script {
        require(!dirty){"请先保存或作废当前修改"}
        return Script(id=UUID.randomUUID().toString()).also {state.value=DraftSnapshot(null,it)}
    }
    fun select(id:String) {
        require(!dirty){"请先保存或作废当前修改再切换脚本"}
        require(readScripts().any {it.id==id}){"脚本不存在"}
        state.value=null;selectSaved(id);begin()
    }
    fun update(script:Script) {
        require(script.meta.minIntervalMs in 50..60000 && script.meta.maxLoopIterations in 1..10000){"间隔或循环上限无效"}
        require(script.meta.repeatCount in 1..script.meta.maxLoopIterations){"循环次数无效"}
        if(script.steps.isNotEmpty())Validator.validate(script)
        val draft=state.value?:readScripts().firstOrNull {it.id==script.id}?.let {DraftSnapshot(it,it)}?:DraftSnapshot(null,script)
        require(draft.script.id==script.id){"草稿对应的脚本已变化"}
        state.value=draft.copy(script=script)
    }
    fun commit():Script {
        val draft=state.value?:current()?.let {DraftSnapshot(it,it)}?:error("没有可保存的脚本")
        val latest=readScripts().firstOrNull {it.id==draft.script.id}
        require(latest==draft.original){"脚本已在其他位置修改，请作废草稿后重新编辑"}
        writeSaved(draft.script)
        state.value=DraftSnapshot(draft.script,draft.script)
        return draft.script
    }
    fun discard(){
        val originalId=state.value?.original?.id;state.value=null
        if(originalId!=null && readScripts().any {it.id==originalId})selectSaved(originalId)
    }
}
