package cn.taprunner

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import cn.taprunner.model.*
import androidx.core.graphics.drawable.toDrawable

class QuickOverlayDialogs(private val hostContext:Context) {
    private val app=TapApp.instance
    private val context get()=app.appearance.viewContext(hostContext)
    private val dialogs=mutableListOf<AlertDialog>()
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
    private fun toast(message:String)=Toast.makeText(context,message,Toast.LENGTH_SHORT).show()
    private fun safe(action:()->Unit){runCatching(action).onFailure {toast(it.message?:"操作失败")}}
    private fun content()=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
    private fun field(root:LinearLayout,label:String,value:String,numeric:Boolean=false):EditText {
        root.addView(TextView(context).apply {text=label;textSize=14f})
        return EditText(context).apply {setText(value);textSize=17f;isSingleLine=true;if(numeric)inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;root.addView(this)}
    }
    private fun show(builder:AlertDialog.Builder,apply:(()->Unit)?=null):AlertDialog {
        val dialog=builder.create();dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener {dialogs.remove(dialog)};dialogs+=dialog;dialog.show();tintNativeTree(dialog.window!!.decorView,app.appearance.palette(context))
        if(apply!=null)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {safe {apply();dialog.dismiss()}}
        return dialog
    }
    fun point(id:String) {
        val script=app.overlayDraft.current()?:return;val step=script.steps.firstOrNull {it.id==id}?:return
        val number=script.steps.indexOf(step)+1;val root=content()
        val delay=field(root,"点击间隔（毫秒）",(step.intervalMs?:script.meta.minIntervalMs).toString(),true)
        val duration=if(step.type=="click")null else field(root,if(step.type=="swipe")"滑动时长（毫秒）"else"按住时长（毫秒）",(if(step.type=="swipe")step.durationMs else step.holdMs).toString(),true)
        root.addView(TextView(context).apply {setText(R.string.point_interval_hint);textSize=12f})
        show(AlertDialog.Builder(context).setTitle("点位 $number").setView(root).setNegativeButton("取消",null)
            .setNeutralButton("删除此点"){_,_ -> safe {val current=app.overlayDraft.current()?:return@safe;app.overlayDraft.update(current.copy(steps=current.steps.filter {it.id!=id}))}}
            .setPositiveButton("应用",null)) {
            val current=app.overlayDraft.current()?:error("没有点位")
            val latest=current.steps.firstOrNull {it.id==id}?:error("点位已删除")
            val interval=delay.text.toString().toLong()
            val updated=when(latest.type){"swipe"->latest.copy(intervalMs=interval,durationMs=duration!!.text.toString().toLong());"longPress"->latest.copy(intervalMs=interval,holdMs=duration!!.text.toString().toLong());else->latest.copy(intervalMs=interval,holdMs=50)}
            app.overlayDraft.update(current.copy(steps=current.steps.map {if(it.id==id)updated else it}))
        }
    }
    fun settings() {
        val script=app.overlayDraft.begin();val root=content()
        val name=field(root,"脚本名称",script.name)
        val interval=field(root,"默认点击间隔（毫秒）",script.meta.minIntervalMs.toString(),true)
        val times=field(root,"循环次数",script.meta.repeatCount.toString(),true)
        root.addView(TextView(context).apply {text="所有点位按编号顺序执行一轮";textSize=12f})
        show(AlertDialog.Builder(context).setTitle("连点设置").setView(root).setNegativeButton("取消",null).setPositiveButton("应用",null)) {
            val current=app.overlayDraft.current()?:error("未选择脚本")
            app.overlayDraft.update(current.copy(name=name.text.toString().ifBlank {"我的脚本"},meta=current.meta.copy(minIntervalMs=interval.text.toString().toLong(),repeatCount=times.text.toString().toInt())))
        }
    }
    fun menu(items:List<Pair<String,()->Unit>>) {
        show(AlertDialog.Builder(context).setTitle("更多").setItems(items.map {it.first}.toTypedArray()){_,which -> safe(items[which].second)})
    }
    fun select() {
        val scripts=app.store.scripts.value
        if(scripts.isEmpty()){toast("还没有保存的脚本");return}
        show(AlertDialog.Builder(context).setTitle("加载脚本").setItems(scripts.map {"${it.name} · ${it.steps.size} 步"}.toTypedArray()){_,index ->
            beforeReplace {app.overlayDraft.select(scripts[index].id)}
        })
    }
    fun beforeReplace(action:()->Unit) {
        if(!app.overlayDraft.dirty){safe(action);return}
        show(AlertDialog.Builder(context).setTitle("当前修改还未保存").setMessage("保留到脚本库，或放弃这次修改。")
            .setPositiveButton("保存后继续"){_,_ -> safe {app.overlayDraft.commit();action()}}
            .setNegativeButton("作废后继续"){_,_ -> safe {app.overlayDraft.discard();action()}}
            .setNeutralButton("取消",null))
    }
    fun deleteScript() {
        val script=app.overlayDraft.current()?:return
        val saved=app.store.scripts.value.any {it.id==script.id}
        show(AlertDialog.Builder(context).setTitle(if(saved)"删除脚本？"else"作废新脚本？").setMessage(script.name)
            .setNegativeButton("取消",null).setPositiveButton("确认删除"){_,_ -> safe {app.overlayDraft.discard();if(saved)app.store.delete(script.id)}})
    }
    fun close(){dialogs.toList().forEach {it.dismiss()};dialogs.clear()}
    fun refreshTheme(){val palette=app.appearance.palette(hostContext);dialogs.forEach {dialog -> dialog.window?.let {it.setBackgroundDrawable(palette.surface.toDrawable());tintNativeTree(it.decorView,palette)}}}
}
