package cn.taprunner

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.view.*
import android.widget.*
import cn.taprunner.engine.*
import cn.taprunner.model.*
import cn.taprunner.model.Point
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import java.util.UUID

class OverlayController(private val service: RunnerService) {
    private val app = TapApp.instance
    private val palette get()=app.appearance.palette(service)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val views = mutableListOf<View>()
    private val status = DragLabel(service)
    private val panel = LinearLayout(service)
    private val panelParams = params(-2, -2).apply { x=dp(8); y=dp(72) }
    private val numbered = NumberedMarkerLayer(service)
    private val handles=DraggablePoints(service,onMove={id,point,end -> guarded {movePoint(id,point,end)}},onTap={id -> if(canEdit())dialogs.point(id)})
    private val dialogs=QuickOverlayDialogs(service)
    private val playButton=Button(service)
    private var pointEntries:List<PointMarker> = emptyList()
    private var showMarkers = true
    private var marker: View? = null
    private var picker: View? = null
    private var capture: View? = null
    private var recordingBusy = false
    private var gestureInFlight = false
    private var editor: FloatingEditor? = null
    private var markersJob: Job? = null
    private var closed = false

    init {
        app.runtime.overlayGestureGate=::allowGestureThroughControls
        // Android 12+ uses window alpha to decide whether touches may pass through
        // application overlays, even when their canvas pixels are transparent.
        add(numbered, params(-1,-1).apply { flags=flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;alpha=.65f })
        panel.orientation=LinearLayout.VERTICAL;panel.setPadding(dp(8),dp(4),dp(8),dp(4));panel.background=background(palette.surface)
        status.setTextColor(palette.text);status.textSize=10f;status.gravity=Gravity.CENTER;status.setPadding(0,dp(4),0,dp(4));panel.addView(status);drag(status,panelParams,panel)
        fun tool(label:String,action:()->Unit)=Button(service).apply {
            text=label;textSize=if(label.length==1)22f else 12f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
            setOnClickListener {guarded(action)};panel.addView(this,LinearLayout.LayoutParams(dp(44),dp(40)))
        }
        playButton.text="▶";playButton.textSize=22f;playButton.setPadding(0,0,0,0);playButton.minWidth=0;playButton.minimumWidth=0
        playButton.setOnClickListener {guarded {
            when(app.engine.state.value.status){Status.RUNNING->app.engine.pause();Status.PAUSED->app.engine.resume();else->run()}
        }};panel.addView(playButton,LinearLayout.LayoutParams(dp(44),dp(40)))
        tool("■"){app.engine.stop("悬浮按钮停止")}
        tool("＋"){addPoint()};tool("−"){removeLastPoint()};tool("⚙"){if(canEdit())dialogs.settings()}
        tool("保存"){if(canEdit()){editor?.applyPending();app.overlayDraft.commit();toast("脚本已保存")}}
        tool("⋯"){more()};tool("×"){service.startService(Intent(service,RunnerService::class.java).setAction("close"))}
        add(panel,panelParams)
        scope.launch {
            combine(app.store.scripts,app.store.selected,app.overlayDraft.state) { scripts,id,draft -> draft?.script?:scripts.firstOrNull {it.id==id} }.collect { script ->
                updateMarkers(script);editor?.refresh(script);render(app.engine.state.value)
            }
        }
        tintNativeTree(panel,palette)
        scope.launch {app.appearance.state.drop(1).collect {panel.background=background(palette.surface);views.forEach {tintNativeTree(it,palette)};editor?.refreshTheme();dialogs.refreshTheme()}}
    }
    private fun dp(value:Int)=(value*service.resources.displayMetrics.density).toInt()
    private fun background(color:Int)=android.graphics.drawable.GradientDrawable().apply { setColor(color);cornerRadius=dp(12).toFloat() }
    private fun params(width:Int,height:Int)=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT).apply {
        gravity=overlayGravity();layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
    private fun button(row:LinearLayout,label:String,action:()->Unit) {
        row.addView(Button(service).apply {text=label;textSize=12f;minWidth=0;minimumWidth=0;setPadding(dp(8),0,dp(8),0);setOnClickListener { guarded(action) }},LinearLayout.LayoutParams(-2,dp(42)))
    }
    private fun guarded(action:()->Unit) {runCatching(action).onFailure {toast(it.message?:"操作失败")}}
    private fun toast(text:String)=Toast.makeText(service,text,Toast.LENGTH_SHORT).show()
    private fun add(view:View,p:WindowManager.LayoutParams) {wm.addView(view,p);views+=view}
    private fun remove(view:View?) {view?.let {if(views.remove(it))runCatching {wm.removeView(it)}}}
    private fun drag(view:View,p:WindowManager.LayoutParams,window:View) {
        var sx=0f;var sy=0f;var ox=0;var oy=0
        val updater=FrameWindowUpdater(wm,window,p)
        view.setOnTouchListener { _,e -> when(e.action) {
            MotionEvent.ACTION_DOWN -> {sx=e.rawX;sy=e.rawY;ox=p.x;oy=p.y}
            MotionEvent.ACTION_MOVE -> {val (w,h)=app.runtime.size();p.x=(ox+e.rawX-sx).toInt().coerceIn(0,maxOf(0,w-window.width.coerceAtLeast(dp(44))));p.y=(oy+e.rawY-sy).toInt().coerceIn(0,maxOf(0,h-window.height.coerceAtLeast(dp(44))));updater.schedule()}
            MotionEvent.ACTION_UP -> {updater.flush();if(kotlin.math.hypot(e.rawX-sx,e.rawY-sy)<dp(6))view.performClick()}
            MotionEvent.ACTION_CANCEL -> updater.flush()
        };true}
    }
    private fun canEdit():Boolean {
        if(recordingBusy){toast("正在回放本次动作，请稍候");return false}
        if(app.engine.state.value.status in listOf(Status.RUNNING,Status.PAUSED)){toast("请先停止脚本再编辑或取点");return false};return true
    }
    private fun run() {
        if(recordingBusy){toast("正在回放本次点击，请稍候");return}
        editor?.applyPending()
        if(app.overlayDraft.current()?.steps.isNullOrEmpty()){toast("先点＋，再点击屏幕创建点位");return}
        finishPicking();editor?.close();editor=null
        service.startService(Intent(service,RunnerService::class.java).setAction("runDraft"))
    }
    fun edit() {
        if(!canEdit())return
        finishPicking();app.overlayDraft.begin();editor?.close()
        editor=FloatingEditor(service,scope,onClose={editor=null;refreshPointVisibility()},onPick={id -> pick(id)},onRecord={record()},onHighlight={id -> numbered.highlight=id;numbered.invalidate()})
        refreshPointVisibility()
    }
    fun render(p:Progress) {
        val state=when(p.status){Status.RUNNING->"运行";Status.PAUSED->"暂停";Status.FAULT->"故障";Status.FINISHED->"完成";else->"就绪"}
        val statusText=if(p.status==Status.RUNNING || p.status==Status.PAUSED)"$state\n${p.round+1}轮"else if(app.overlayDraft.dirty)"未保存"else state
        if(status.text.toString()!=statusText)status.text=statusText
        val playText=if(p.status==Status.RUNNING)"Ⅱ"else"▶"
        if(playButton.text.toString()!=playText)playButton.text=playText
        if(p.status==Status.RUNNING || p.status==Status.PAUSED)numbered.highlight=p.step
        refreshPointVisibility()
    }
    private fun refreshPointVisibility() {
        if(closed)return
        val idle=app.engine.state.value.status !in listOf(Status.RUNNING,Status.PAUSED)
        val interactive=showMarkers && idle && marker==null && capture==null && editor==null && !gestureInFlight
        handles.render(pointEntries,interactive)
        numbered.visibility=if(showMarkers && !interactive && !recordingBusy && !gestureInFlight)View.VISIBLE else View.INVISIBLE
    }
    private fun updateMarkers(script:Script?) {
        markersJob?.cancel()
        val entries=script?.steps?.mapIndexedNotNull {index,step ->
            if(step.type !in listOf("click","longPress","swipe"))null
            else {
                val point=if(step.type=="swipe")Point(step.from[0],step.from[1])else step.fallback?:if(step.target==null)Point(step.x,step.y)else null
                PointMarker(step.id,index+1,point,if(step.type=="swipe")Point(step.to[0],step.to[1])else null)
            }
        }.orEmpty()
        pointEntries=entries.filter {it.point!=null};numbered.markers=pointEntries;numbered.invalidate();refreshPointVisibility()
        if(entries.any {it.point==null})markersJob=scope.launch {
            val resolved=entries.map {entry -> if(entry.point!=null)entry else {
                val target=script?.steps?.firstOrNull {it.id==entry.id}?.target;entry.copy(point=target?.let {app.runtime.find(it)})
            }}
            pointEntries=resolved.filter {it.point!=null};numbered.markers=pointEntries;numbered.invalidate();refreshPointVisibility()
        }
    }
    fun newQuick(single:Boolean) {
        if(!canEdit())return
        dialogs.beforeReplace {
            finishPicking();editor?.close();editor=null
            val draft=app.overlayDraft.create()
            val title=if(single)"单点连点"else"多点连点"
            val number=app.store.scripts.value.count {it.name.startsWith(title)}+1
            app.overlayDraft.update(draft.copy(name="$title $number",meta=draft.meta.copy(repeatCount=if(single)100 else 1,minIntervalMs=if(single)200 else 500),steps=emptyList()))
            toast("切到目标页面，点＋后直接点击或滑动；完成并保存")
        }
    }
    private fun addPoint(swipe:Boolean=false) {
        if(!canEdit() || recordingBusy)return
        if(!swipe){record();return}
        finishPicking();editor?.close();editor=null
        val script=app.overlayDraft.begin();val count=script.steps.count {it.type in listOf("click","longPress","swipe")}
        val point=Point((.5+(count%3-1)*.08).coerceIn(.1,.9),(.5+(count/3%3)*.08).coerceIn(.1,.9))
        val step=if(swipe)Step(nextId(),"swipe",from=listOf(point.x,.7),to=listOf(point.x,.3),note="滑动点位")else Step(nextId(),x=point.x,y=point.y,note="点击点位")
        app.overlayDraft.update(PointRecording.append(script,step));numbered.highlight=step.id
    }
    private fun movePoint(id:String,point:Point,end:Boolean) {
        if(!canEdit())return
        val script=app.overlayDraft.begin()
        val steps=script.steps.map {step -> if(step.id!=id)step else if(step.type=="swipe") {
            if(end)step.copy(to=listOf(point.x,point.y))else step.copy(from=listOf(point.x,point.y))
        }else step.copy(x=point.x,y=point.y,fallback=point,target=null)}
        app.overlayDraft.update(script.copy(steps=steps));numbered.highlight=id
    }
    private fun removeLastPoint() {
        if(!canEdit())return
        val script=app.overlayDraft.current()?:return
        val point=script.steps.lastOrNull {it.type in listOf("click","longPress","swipe")}
        if(point==null){toast("还没有点位");return}
        app.overlayDraft.update(script.copy(steps=script.steps.filter {it.id!=point.id}))
    }
    private fun more() {
        if(!canEdit())return
        dialogs.menu(listOf(
            "新建单点连点" to {newQuick(true)},"新建多点连点" to {newQuick(false)},"加载已保存脚本" to {dialogs.select()},
            "添加滑动" to {addPoint(true)},"点选录制" to {record()},"准星取点" to {pick()},"步骤列表（高级）" to {edit()},
            "仅标点（不操作页面）" to {record(replay=false)},
            "作废未保存修改" to {app.overlayDraft.discard();toast("未保存修改已作废")},"删除当前脚本" to {dialogs.deleteScript()},
            "显示/隐藏标记" to {showMarkers=!showMarkers;refreshPointVisibility()},
            "打开应用" to {service.startActivity(Intent(service,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        ))
    }
    private fun nextId()="s_${UUID.randomUUID().toString().take(8)}"
    private fun normalized(x:Float,y:Float):Point {
        val (w,h)=app.runtime.size();return Point((x/(w-1).toDouble()).coerceIn(0.0,1.0),(y/(h-1).toDouble()).coerceIn(0.0,1.0))
    }
    fun pick(stepId:String?=null) {
        if(!canEdit() || recordingBusy)return
        finishPicking();editor?.close();editor=null;if(app.overlayDraft.current()==null)app.overlayDraft.create()
        val original=stepId?.let {id -> app.overlayDraft.current()?.steps?.firstOrNull {it.id==id}}
        val (w,h)=app.runtime.size();val start=original?.fallback?:original?.let {Point(it.x,it.y)}?:Point(.5,.5)
        val crossSize=dp(42)
        val cross=object:TouchSurface(service) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xFFEB553A.toInt();strokeWidth=dp(2).toFloat();style=Paint.Style.STROKE}
            override fun onDraw(c:Canvas){c.drawLine(0f,height/2f,width.toFloat(),height/2f,paint);c.drawLine(width/2f,0f,width/2f,height.toFloat(),paint);c.drawCircle(width/2f,height/2f,dp(8).toFloat(),paint)}
        }
        val cp=params(crossSize,crossSize).apply {x=(start.x*(w-1)).toInt()-crossSize/2;y=(start.y*(h-1)).toInt()-crossSize/2}
        marker=cross;add(cross,cp);drag(cross,cp,cross);refreshPointVisibility()
        val controls=LinearLayout(service).apply {orientation=LinearLayout.VERTICAL;background=background(palette.surface);setPadding(dp(8),dp(4),dp(8),dp(4))}
        val hint=TextView(service).apply {text=if(original==null)"拖动准星，保存后显示彩色步骤编号"else"重选步骤 ${app.overlayDraft.current()?.steps?.indexOf(original)?.plus(1)} 的点位";setTextColor(palette.text);textSize=12f};controls.addView(hint)
        val row=LinearLayout(service);controls.addView(row)
        button(row,if(original==null)"取此点"else"更新点位") {
            if(!recordingBusy){
                val location=IntArray(2);cross.getLocationOnScreen(location);val point=normalized(location[0]+crossSize/2f,location[1]+crossSize/2f)
                recordingBusy=true;cross.visibility=View.INVISIBLE
                scope.launch {
                    try {
                        val target=app.runtime.reverse(point);val current=app.overlayDraft.current()?:error("未选择脚本")
                        val step=(original?:Step(nextId())).copy(x=point.x,y=point.y,target=target,fallback=point,note=original?.note?:target?.value?:"标定点")
                        app.overlayDraft.update(if(original==null)PointRecording.append(current,step)else current.copy(steps=current.steps.map {if(it.id==step.id)step else it}))
                        numbered.highlight=step.id;hint.text=service.getString(R.string.step_marked,app.overlayDraft.current()?.steps?.indexOfFirst {it.id==step.id}?.plus(1)?:0)
                        if(original!=null){finishPicking();edit()}
                    }catch(e:CancellationException){throw e}catch(e:Exception){hint.text=e.message}
                    finally {recordingBusy=false;if(marker===cross)cross.visibility=View.VISIBLE}
                }
            }
        }
        button(row,"点选模式"){record()};button(row,"完成"){finishPicking()}
        val saveRow=LinearLayout(service);controls.addView(saveRow)
        button(saveRow,"一键保存"){if(!recordingBusy){app.overlayDraft.commit();toast("脚本已保存")}}
        button(saveRow,"作废修改"){if(!recordingBusy){app.overlayDraft.discard();finishPicking();toast("未保存修改已作废")}}
        button(saveRow,"悬浮编辑"){if(!recordingBusy)edit()}
        picker=controls;add(controls,params(-2,-2).apply {gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=dp(40)})
    }
    fun record(replay:Boolean=true) {
        if(!canEdit() || recordingBusy)return
        if(replay)app.runtime.healthy("")?.let {toast(it);return}
        finishPicking();editor?.close();editor=null;if(app.overlayDraft.current()==null)app.overlayDraft.create();panel.visibility=View.GONE
        val recordingScriptId=app.overlayDraft.current()?.id
        data class Recorded(val before:Script,val after:Script,val id:String,val startedAt:Long)
        val recorded=mutableListOf<Recorded>()
        val layer=object:TouchSurface(service){override fun onDraw(canvas:Canvas){canvas.drawColor(0x18000000)};override fun performClick():Boolean=super.performClick()}
        val captureParams=params(-1,-1)
        val hint=DragLabel(service)
        var downX=0f;var downY=0f;var downAt=0L;var validTouch=false
        var pending:Step?=null
        fun restoreCapture() {
            if(capture===layer && !closed){captureParams.flags=captureParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv();captureParams.alpha=1f;layer.visibility=View.VISIBLE;wm.updateViewLayout(layer,captureParams)}
        }
        fun updateHint(){hint.text=service.getString(if(replay)R.string.recording_count else R.string.marking_count,recorded.size)}
        layer.setOnClickListener {
            val step=pending?:return@setOnClickListener
            if(recordingBusy)return@setOnClickListener
            recordingBusy=true;refreshPointVisibility()
            scope.launch {
                try {
                    require(app.overlayDraft.current()?.id==recordingScriptId){"录制脚本已切换，请重新开始"}
                    if(replay)app.runtime.healthy("")?.let {error(it)}
                    val current=app.overlayDraft.current()?:error("未选择脚本")
                    val startedAt=app.runtime.now();val last=recorded.lastOrNull()
                    val updated=PointRecording.append(current,step,if(replay)last?.id else null,if(replay)last?.let {startedAt-it.startedAt} else null)
                    app.overlayDraft.update(updated);recorded+=Recorded(current,updated,step.id,startedAt);numbered.highlight=step.id;updateHint()
                    if(replay){
                        captureParams.flags=captureParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;captureParams.alpha=0f;layer.visibility=View.INVISIBLE;wm.updateViewLayout(layer,captureParams)
                        delay(80)
                        require(capture===layer && app.overlayDraft.current()?.id==recordingScriptId){"录入已结束"}
                        val from=if(step.type=="swipe")Point(step.from[0],step.from[1])else Point(step.x,step.y)
                        val to=if(step.type=="swipe")Point(step.to[0],step.to[1])else null
                        val ok=app.runtime.gesture(from,to,if(step.type=="swipe")step.durationMs else step.holdMs)
                        app.store.log("recording","${step.id} ${step.type} replay=$ok")
                        if(!ok)toast("已保留点位，但动作未完成；请检查页面或撤销此点")
                    }
                }catch(e:CancellationException){throw e}catch(e:Exception){toast(e.message?:"取点失败")}
                finally {recordingBusy=false;restoreCapture();refreshPointVisibility()}
            }
        }
        layer.setOnTouchListener {_,event -> when(event.actionMasked){
            MotionEvent.ACTION_DOWN -> {downX=event.rawX;downY=event.rawY;downAt=app.runtime.now();validTouch=!recordingBusy}
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> {validTouch=false;pending=null}
            MotionEvent.ACTION_UP -> {
                if(validTouch && !recordingBusy){
                    val from=normalized(downX,downY);val to=normalized(event.rawX,event.rawY);val duration=(app.runtime.now()-downAt).coerceIn(1,60000)
                    pending=when {
                        kotlin.math.hypot(event.rawX-downX,event.rawY-downY)>ViewConfiguration.get(service).scaledTouchSlop -> Step(nextId(),"swipe",from=listOf(from.x,from.y),to=listOf(to.x,to.y),durationMs=duration,note="录入滑动")
                        replay && duration>=500 -> Step(nextId(),"longPress",x=to.x,y=to.y,holdMs=duration,note="录入长按")
                        else -> Step(nextId(),x=to.x,y=to.y,holdMs=50,note=if(replay)"录入点击"else"点击点位")
                    }
                    layer.performClick()
                }else if(recordingBusy)toast("动作回放中，请稍候再录入下一点")
                validTouch=false
            }
        };true}
        capture=layer;add(layer,captureParams);refreshPointVisibility()
        val controls=LinearLayout(service).apply {orientation=LinearLayout.VERTICAL;background=background(palette.surface);setPadding(dp(8),dp(4),dp(8),dp(4))}
        hint.textSize=11f;hint.setPadding(0,dp(4),0,dp(4));controls.addView(hint);updateHint()
        controls.addView(TextView(service).apply {setText(if(replay)R.string.recording_hint else R.string.marking_hint);textSize=11f;setTextColor(palette.text)})
        val row=LinearLayout(service);controls.addView(row)
        button(row,"撤销上一点"){
            if(!recordingBusy)app.overlayDraft.current()?.let {current ->
                val last=recorded.lastOrNull()
                if(last==null)toast("本次录入还没有新动作")
                else {require(current==last.after){"脚本已在其他位置修改，请重新录入"};app.overlayDraft.update(last.before);recorded.removeAt(recorded.lastIndex);updateHint()}
            }
        }
        button(row,"完成并保存"){if(!recordingBusy){app.overlayDraft.commit();finishPicking();toast("整段脚本已保存；请回到起始页面再运行")}}
        val saveRow=LinearLayout(service);controls.addView(saveRow)
        button(saveRow,"作废修改"){if(!recordingBusy){app.overlayDraft.discard();finishPicking();toast("未保存修改已作废")}}
        val (screenWidth,screenHeight)=app.runtime.size();val controlsParams=params(dp(220),-2).apply {x=maxOf(0,screenWidth-dp(228));y=maxOf(0,screenHeight-dp(190))}
        drag(hint,controlsParams,controls);picker=controls;add(controls,controlsParams);tintNativeTree(controls,palette)
    }
    /** Hide intersecting overlays, including non-touchable canvases, until dispatch completes. */
    private fun allowGestureThroughControls(from:Point,to:Point?):(() -> Unit)? {
        if(closed)return null
        refreshPointVisibility()
        val (w,h)=app.runtime.size();val end=to?:from
        val path=RectF((minOf(from.x,end.x)*(w-1)).toFloat()-2,(minOf(from.y,end.y)*(h-1)).toFloat()-2,(maxOf(from.x,end.x)*(w-1)).toFloat()+2,(maxOf(from.y,end.y)*(h-1)).toFloat()+2)
        val affected=views.filter {view ->
            val parameters=view.layoutParams as? WindowManager.LayoutParams
            if(view.visibility!=View.VISIBLE || parameters==null)false
            else {val origin=IntArray(2);view.getLocationOnScreen(origin);RectF.intersects(path,RectF(origin[0].toFloat(),origin[1].toFloat(),(origin[0]+view.width).toFloat(),(origin[1]+view.height).toFloat()))}
        }.map {view -> val parameters=view.layoutParams as WindowManager.LayoutParams; Triple(view,parameters,Pair(parameters.flags,parameters.alpha))}
        if(affected.isEmpty())return null
        gestureInFlight=true
        refreshPointVisibility()
        affected.forEach {(view,parameters,_) -> parameters.flags=parameters.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;parameters.alpha=0f;view.visibility=View.INVISIBLE;wm.updateViewLayout(view,parameters)}
        return {
            affected.forEach {(view,parameters,previous) -> if(!closed && view in views && view.isAttachedToWindow){parameters.flags=previous.first;parameters.alpha=previous.second;view.visibility=View.VISIBLE;runCatching{wm.updateViewLayout(view,parameters)}}}
            gestureInFlight=false
            refreshPointVisibility()
        }
    }
    private fun finishPicking(){remove(marker);remove(picker);remove(capture);marker=null;picker=null;capture=null;panel.visibility=View.VISIBLE;refreshPointVisibility()}
    fun close(){if(closed)return;closed=true;app.runtime.overlayGestureGate=null;scope.cancel();dialogs.close();editor?.close();editor=null;handles.close();views.toList().forEach(::remove)}
}
