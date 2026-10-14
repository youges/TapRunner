package cn.taprunner

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import cn.taprunner.model.*
import cn.taprunner.model.Point
import cn.taprunner.model.Target
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/** Views editor owned by the overlay service; no Activity replaces the target app. */
class FloatingEditor(
    private val hostContext:Context,
    @Suppress("UNUSED_PARAMETER") scope:CoroutineScope,
    private val onClose:()->Unit,
    private val onPick:(String)->Unit,
    private val onRecord:()->Unit,
    private val onHighlight:(String)->Unit
) {
    private val app=TapApp.instance
    private val context=app.appearance.viewContext(hostContext)
    private val palette get()=app.appearance.palette(hostContext)
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root=LinearLayout(context)
    private val screenSize=app.runtime.size()
    private val screenWidth=screenSize.first
    private val screenHeight=screenSize.second
    private var expanded=false
    private var keyboardInset=0
    private val window=WindowManager.LayoutParams(minOf(dp(280),(screenWidth*.75).toInt()),minOf(dp(400),(screenHeight*.48).toInt()),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT).apply {
        gravity=overlayGravity();x=dp(12);y=dp(185)
        layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }
    private var closed=false
    private var formOpen=false
    private var shownScript=""
    private var applyForm:(()->Unit)?=null
    init {
        root.orientation=LinearLayout.VERTICAL
        root.background=GradientDrawable().apply {setColor(palette.surface);cornerRadius=dp(14).toFloat();setStroke(dp(1),palette.border)}
        root.setOnApplyWindowInsetsListener {_,insets -> val inset=if(insets.isVisible(WindowInsets.Type.ime()))insets.getInsets(WindowInsets.Type.ime()).bottom else 0;if(inset!=keyboardInset){keyboardInset=inset;resizeEditor()};insets}
        wm.addView(root,window);resizeEditor();refresh(app.overlayDraft.current())
    }
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    private fun safe(action:()->Unit) {runCatching(action).onFailure {Toast.makeText(context,it.message?:"操作失败",Toast.LENGTH_LONG).show()}}
    private fun column()=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(6))}
    private fun row(parent:LinearLayout)=LinearLayout(context).also {parent.addView(it)}
    private fun text(parent:LinearLayout,value:String,size:Float=13f,color:Int=palette.text):TextView = TextView(context).apply {text=value;textSize=size;setTextColor(if(color==Color.DKGRAY)palette.muted else color);setPadding(0,dp(3),0,dp(3));parent.addView(this)}
    private fun button(parent:LinearLayout,label:String,action:()->Unit):Button=Button(context).apply {
        text=label;textSize=12f;minWidth=0;minimumWidth=0;setPadding(dp(7),0,dp(7),0);isSingleLine=true;setTextColor(palette.text);backgroundTintList=android.content.res.ColorStateList.valueOf(palette.raised)
        setOnClickListener {safe(action)};parent.addView(this,LinearLayout.LayoutParams(-2,dp(40)))
    }
    private fun resizeEditor() {
        val old=listOf(window.width,window.height,window.x,window.y)
        window.width=if(expanded)minOf(dp(340),screenWidth-dp(24))else minOf(dp(280),(screenWidth*.75).toInt())
        val desiredHeight=if(expanded)minOf(dp(540),(screenHeight*.68).toInt())else minOf(dp(400),(screenHeight*.48).toInt())
        window.height=minOf(desiredHeight,maxOf(dp(160),screenHeight-keyboardInset-dp(48)))
        window.x=window.x.coerceIn(0,maxOf(0,screenWidth-window.width))
        window.y=window.y.coerceIn(dp(24),maxOf(dp(24),screenHeight-keyboardInset-window.height-dp(24)))
        if(old!=listOf(window.width,window.height,window.x,window.y))wm.updateViewLayout(root,window)
    }
    private fun header(title:String,back:(()->Unit)?=null) {
        val bar=LinearLayout(context).apply {tag="accentBar";setBackgroundColor(palette.accent);setPadding(dp(10),dp(4),dp(6),dp(4))};root.addView(bar)
        val label=DragLabel(context).apply {tag="onAccent";text=title;textSize=14f;setTextColor(palette.onAccent);gravity=Gravity.CENTER_VERTICAL;isSingleLine=true;ellipsize=TextUtils.TruncateAt.END};bar.addView(label,LinearLayout.LayoutParams(0,dp(40),1f))
        val updater=FrameWindowUpdater(wm,root,window)
        var downX=0f;var downY=0f;var startX=0;var startY=0
        label.setOnTouchListener {_,event -> when(event.action){
            MotionEvent.ACTION_DOWN -> {downX=event.rawX;downY=event.rawY;startX=window.x;startY=window.y}
            MotionEvent.ACTION_MOVE -> {window.x=(startX+event.rawX-downX).toInt().coerceIn(0,maxOf(0,screenWidth-window.width));window.y=(startY+event.rawY-downY).toInt().coerceIn(dp(24),maxOf(dp(24),screenHeight-keyboardInset-window.height-dp(24)));updater.schedule()}
            MotionEvent.ACTION_UP -> {updater.flush();if(kotlin.math.hypot(event.rawX-downX,event.rawY-downY)<dp(6))label.performClick()}
            MotionEvent.ACTION_CANCEL -> updater.flush()
        };true}
        if(back!=null)button(bar,"返回",back)
        val sizeButton=button(bar,if(expanded)"缩小"else"放大"){}
        sizeButton.setOnClickListener {safe {expanded=!expanded;resizeEditor();sizeButton.text=if(expanded)"缩小"else"放大"}}
        button(bar,"收起"){applyForm?.invoke();close()}
    }
    private fun scroll():LinearLayout {
        val content=column();root.addView(ScrollView(context).apply {isFillViewport=true;addView(content)},LinearLayout.LayoutParams(-1,0,1f));return content
    }
    private fun focusable(enabled:Boolean) {
        if(closed)return
        window.flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or if(enabled)WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wm.updateViewLayout(root,window)
        if(!enabled)(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0)
    }
    fun refresh(script:Script?) {
        if(closed)return
        if(script?.id!=shownScript){formOpen=false;shownScript=script?.id.orEmpty()}
        if(formOpen)return
        list(script)
    }
    private fun list(script:Script?) {
        applyForm=null;formOpen=false;focusable(false);root.removeAllViews();header("悬浮编辑 · ${if(app.overlayDraft.dirty)"未保存"else"已保存"}")
        val content=scroll()
        val saved=app.store.scripts.value
        val scripts=saved.map {if(it.id==script?.id)script else it}+if(script!=null && saved.none {it.id==script.id})listOf(script)else emptyList()
        if(scripts.isNotEmpty()) {
            val spinner=Spinner(context)
            spinner.adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,scripts.map {it.name})
            spinner.setSelection(scripts.indexOfFirst {it.id==script?.id}.coerceAtLeast(0))
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){scripts.getOrNull(position)?.let {chosen ->
                    if(chosen.id!=app.overlayDraft.current()?.id){safe {app.overlayDraft.select(chosen.id)};spinner.setSelection(scripts.indexOfFirst {it.id==app.overlayDraft.current()?.id}.coerceAtLeast(0))}
                }}
                override fun onNothingSelected(parent:AdapterView<*>?){}
            };content.addView(spinner)
        }
        val actions=row(content)
        button(actions,"新建"){app.overlayDraft.create()};button(actions,"设置"){script?.let {settings(it)}}
        button(actions,"点选"){close();onRecord()};button(actions,"回应用"){context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        if(script==null){footer();return}
        text(content,"编号按步骤顺序自动更新 · 间隔 ${script.meta.minIntervalMs}ms",11f,Color.DKGRAY)
        button(row(content),"＋新增步骤"){form(null)}
        if(script.steps.isEmpty())text(content,"点选或准星取点后，这里会出现步骤 1、2、3…")
        script.steps.forEachIndexed {index,step ->
            val group=column().apply {tag="stepCard";background=GradientDrawable().apply {setColor(palette.raised);cornerRadius=dp(8).toFloat()}}
            content.addView(group,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5)})
            val number=index+1;val title=row(group)
            title.addView(TextView(context).apply {tag="fixed";text=context.getString(R.string.step_number,number);textSize=14f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;background=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(NumberedMarkerLayer.color(number))}},LinearLayout.LayoutParams(dp(30),dp(30)))
            title.addView(TextView(context).apply {text=context.getString(R.string.step_title,label(step.type),step.note);textSize=13f;setTextColor(palette.text);setOnClickListener {onHighlight(step.id)}},LinearLayout.LayoutParams(0,-2,1f))
            text(group,summary(step),11f,Color.DKGRAY)
            val tools=row(group)
            button(tools,"编辑"){onHighlight(step.id);form(step)}
            button(tools,"↑"){move(step.id,-1)};button(tools,"↓"){move(step.id,1)}
            button(tools,"删除"){
                val current=app.overlayDraft.current()?:return@button
                app.overlayDraft.update(current.copy(steps=current.steps.filter {it.id!=step.id}))
            }
        }
        footer()
    }
    private fun footer() {
        val bar=column()
        text(bar,if(app.overlayDraft.dirty || formOpen)"草稿预览 · 一键保存后写入脚本"else"已保存 · 作废只撤销未保存修改",11f,Color.DKGRAY)
        val actions=row(bar)
        button(actions,"保存"){
            applyForm?.invoke();app.overlayDraft.commit();list(app.overlayDraft.current())
            Toast.makeText(context,"脚本已保存",Toast.LENGTH_SHORT).show()
        }
        button(actions,"作废"){applyForm=null;app.overlayDraft.discard();list(app.overlayDraft.current())}
        button(actions,"删除"){deleteScript()}
        for(index in 0 until actions.childCount)actions.getChildAt(index).layoutParams=LinearLayout.LayoutParams(0,dp(40),1f)
        root.addView(bar,LinearLayout.LayoutParams(-1,-2))
    }
    private fun deleteScript() {
        val script=app.overlayDraft.current()?:return
        val exists=app.store.scripts.value.any {it.id==script.id}
        val dialog=android.app.AlertDialog.Builder(context).setTitle(if(exists)"删除脚本？"else"作废新脚本？")
            .setMessage("${script.name}\n${if(exists)"删除脚本并放弃未保存修改。"else"此脚本还未保存，将放弃全部草稿。"}")
            .setNegativeButton("取消",null).setPositiveButton("确认删除"){_,_ -> safe {
                applyForm=null;app.overlayDraft.discard();if(exists)app.store.delete(script.id);list(app.overlayDraft.current())
            }}.create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show()
    }
    private fun move(id:String,delta:Int) {
        val script=app.overlayDraft.current()?:return;val steps=script.steps.toMutableList();val index=steps.indexOfFirst {it.id==id};val destination=index+delta
        if(index>=0 && destination in steps.indices){val step=steps.removeAt(index);steps.add(destination,step);app.overlayDraft.update(script.copy(steps=steps));onHighlight(id)}
    }
    private fun field(parent:LinearLayout,label:String,value:String,numeric:Boolean=false):EditText {
        text(parent,label,12f)
        return EditText(context).apply {
            setText(value);textSize=14f;isSingleLine=true;setTextColor(palette.text);backgroundTintList=android.content.res.ColorStateList.valueOf(palette.accent)
            if(numeric)inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            parent.addView(this,LinearLayout.LayoutParams(-1,dp(42)))
        }
    }
    private fun settings(script:Script) {
        formOpen=true;root.removeAllViews();header("脚本设置",{list(app.overlayDraft.current())});val content=scroll();focusable(true)
        val name=field(content,"脚本名称",script.name)
        val interval=field(content,"点击开始到下次点击的间隔（毫秒）",script.meta.minIntervalMs.toString(),true)
        val pkg=field(content,"目标应用包名（可留空）",script.meta.expectedPkg)
        val applySettings={
            val current=app.overlayDraft.current()?:error("未选择脚本")
            require(current.id==script.id){"脚本已切换，请重新编辑"}
            app.overlayDraft.update(current.copy(name=name.text.toString().ifBlank {"新脚本"},meta=current.meta.copy(minIntervalMs=interval.text.toString().toLong(),expectedPkg=pkg.text.toString())))
            list(app.overlayDraft.current())
        }
        applyForm=applySettings;button(row(content),"预览设置"){applySettings()};footer()
    }
    private fun form(original:Step?,chosenType:String=original?.type?:"click") {
        val script=app.overlayDraft.current()?:return
        formOpen=true;root.removeAllViews()
        val number=original?.let {script.steps.indexOfFirst {it.id==original.id}+1}?:script.steps.size+1
        header("步骤 $number · ${if(original==null)"新增"else"编辑"}",{list(app.overlayDraft.current())})
        val content=scroll();focusable(true)
        val types=listOf("click","longPress","swipe","delay","waitText","loop","key","inputText","launchApp","log","stop","waitImage","if")
        val typeSpinner=Spinner(context).apply {adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,types.map(::label));setSelection(types.indexOf(chosenType).coerceAtLeast(0))}
        typeSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){val type=types[position];if(type!=chosenType)form(original,type)}
            override fun onNothingSelected(parent:AdapterView<*>?){}
        };content.addView(typeSpinner)
        val note=field(content,"备注",original?.note.orEmpty())
        val inputs=mutableMapOf<String,EditText>()
        fun input(key:String,label:String,value:String,numeric:Boolean=false){inputs[key]=field(content,label,value,numeric)}
        val base=original?:Step("s_${UUID.randomUUID().toString().take(8)}")
        var nodePriority:CheckBox?=null
        val selectedChildren=mutableListOf<Pair<String,CheckBox>>()
        when(chosenType){
            "click","longPress" -> {
                input("x","X（0～1，左到右）",(base.fallback?.x?:base.x).toString(),true);input("y","Y（0～1，上到下）",(base.fallback?.y?:base.y).toString(),true)
                input("hold","按住时长（毫秒）",(if(chosenType=="longPress")maxOf(500,base.holdMs)else base.holdMs).toString(),true)
                base.target?.let {target -> nodePriority=CheckBox(context).apply {text=context.getString(R.string.node_priority,target.value);isChecked=true;content.addView(this)}}
                if(original!=null)button(row(content),"在目标页面重选点位"){applyForm?.invoke();close();onPick(original.id)}
                else text(content,"可先保存，再用重选点位或点选录制定位。",11f)
            }
            "swipe" -> {input("x","起点 X",base.from[0].toString(),true);input("y","起点 Y",base.from[1].toString(),true);input("x2","终点 X",base.to[0].toString(),true);input("y2","终点 Y",base.to[1].toString(),true);input("duration","滑动时长（毫秒）",base.durationMs.toString(),true)}
            "delay" -> input("ms","等待时长（毫秒）",base.ms.toString(),true)
            "waitText" -> {input("value","等待的文字",base.target?.value?:base.text);input("timeout","超时（毫秒）",base.timeoutMs.toString(),true);input("find","匹配类型 text / id / desc",base.target?.find?:"text");input("mode","匹配方式 contains / equals / regex",base.target?.mode?:base.mode)}
            "key" -> input("key","系统按键 back / home / recents",base.key)
            "inputText" -> {input("find","控件类型 text / id / desc",base.target?.find?:"text");input("target","目标控件文字或 ID",base.target?.value.orEmpty());input("text","输入内容",base.text)}
            "launchApp" -> {input("pkg","应用包名",base.pkg);input("wait","启动后等待（毫秒）",base.waitMs.toString(),true)}
            "log" -> input("message","日志内容",base.message)
            "stop" -> input("reason","停止提示",base.reason)
            "loop" -> {
                input("times","循环次数（有限）",base.times.toString(),true);text(content,"选择循环内的步骤：",12f)
                script.steps.filter {it.id!=original?.id}.forEach {child ->
                    val check=CheckBox(context).apply {text=context.getString(R.string.child_step,script.steps.indexOf(child)+1,label(child.type),child.note);isChecked=child.id in base.body;content.addView(this)}
                    selectedChildren+=child.id to check
                }
            }
            "waitImage" -> {input("template","本地模板文件名",base.template);input("threshold","匹配阈值",base.threshold.toString(),true);input("timeout","超时（毫秒）",base.timeoutMs.toString(),true);text(content,"新建图像模板请使用主界面的截图功能。",11f)}
            "if" -> text(content,"条件分支请在主界面高级 JSON 编辑；这里可修改备注。",12f)
        }
        val applyStep={
            fun value(key:String)=inputs.getValue(key).text.toString()
            val current=app.overlayDraft.current()?:error("未选择脚本");require(current.id==script.id){"脚本已切换，请重新编辑"}
            val step=when(chosenType){
                "click","longPress" -> {
                    val point=Point(value("x").toDouble(),value("y").toDouble())
                    val oldPoint=base.fallback?:Point(base.x,base.y)
                    val target=if(nodePriority?.isChecked==true && point==oldPoint)base.target else null
                    base.copy(type=chosenType,x=point.x,y=point.y,fallback=point,target=target,holdMs=value("hold").toLong())
                }
                "swipe" -> base.copy(type=chosenType,from=listOf(value("x").toDouble(),value("y").toDouble()),to=listOf(value("x2").toDouble(),value("y2").toDouble()),durationMs=value("duration").toLong())
                "delay" -> base.copy(type=chosenType,ms=value("ms").toLong())
                "waitText" -> {require(value("find") in listOf("text","id","desc")){"匹配类型无效"};require(value("mode") in listOf("contains","equals","regex")){"匹配方式无效"};base.copy(type=chosenType,target=Target(value("find"),value("value"),value("mode")),text=value("value"),timeoutMs=value("timeout").toLong())}
                "key" -> {require(value("key") in listOf("back","home","recents")){"系统按键无效"};base.copy(type=chosenType,key=value("key"))}
                "inputText" -> {require(value("find") in listOf("text","id","desc"));base.copy(type=chosenType,target=Target(value("find"),value("target")),text=value("text"))}
                "launchApp" -> base.copy(type=chosenType,pkg=value("pkg"),waitMs=value("wait").toLong())
                "log" -> base.copy(type=chosenType,message=value("message"))
                "stop" -> base.copy(type=chosenType,reason=value("reason"))
                "loop" -> base.copy(type=chosenType,times=value("times").toInt(),body=selectedChildren.filter {it.second.isChecked}.map {it.first})
                "waitImage" -> base.copy(type=chosenType,template=value("template"),threshold=value("threshold").toDouble(),timeoutMs=value("timeout").toLong())
                else -> base.copy(type=chosenType)
            }.copy(note=note.text.toString())
            app.overlayDraft.update(current.copy(steps=if(original==null)current.steps+step else current.steps.map {if(it.id==original.id)step else it}))
            onHighlight(step.id);list(app.overlayDraft.current())
        }
        applyForm=applyStep;button(row(content),"预览步骤 $number"){applyStep()};footer()
    }
    private fun label(type:String)=mapOf("click" to "点击","longPress" to "长按","swipe" to "滑动","delay" to "等待","waitText" to "等待文字/控件","loop" to "有限循环","key" to "系统按键","inputText" to "输入文字","launchApp" to "打开应用","log" to "日志","stop" to "停止","waitImage" to "等待图像","if" to "条件分支")[type]?:type
    private fun summary(step:Step)=when(step.type){
        "click","longPress" -> "坐标 %.3f, %.3f · %dms%s".format(step.fallback?.x?:step.x,step.fallback?.y?:step.y,step.holdMs,if(step.target!=null)" · 控件优先"else"")
        "delay" -> "${step.ms}ms"
        "loop" -> "${step.times} 轮 · ${step.body.size} 个子步骤"
        "waitText" -> "${step.target?.value?:step.text} · 超时 ${step.timeoutMs}ms"
        else -> step.type
    }
    fun close(){if(closed)return;(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0);closed=true;runCatching {wm.removeView(root)};onClose()}
    fun applyPending(){applyForm?.invoke()}
    fun refreshTheme() {
        (root.background as? GradientDrawable)?.apply {setColor(palette.surface);setStroke(dp(1),palette.border)}
        fun colorGroups(view:View){if(view.tag=="accentBar")view.setBackgroundColor(palette.accent);if(view.tag=="stepCard")(view.background as? GradientDrawable)?.setColor(palette.raised);if(view is ViewGroup)for(index in 0 until view.childCount)colorGroups(view.getChildAt(index))}
        colorGroups(root);tintNativeTree(root,palette)
    }
}
