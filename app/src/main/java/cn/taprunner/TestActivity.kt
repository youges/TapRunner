package cn.taprunner

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import cn.taprunner.model.*
import cn.taprunner.model.Target
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import cn.taprunner.engine.Status

class TestActivity: ComponentActivity() {
    private var count=0
    private var monitor:Job?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        val app=application as TapApp
        val palette=app.appearance.palette(this)
        setTheme(if(palette.dark)R.style.Theme_TapRunner_Dark else R.style.Theme_TapRunner_Light)
        super.onCreate(savedInstanceState)
        val density=resources.displayMetrics.density
        fun dp(value:Int)=(value*density).toInt()
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(32),dp(22),dp(24));setBackgroundColor(palette.background)}
        var scrollEvents=0
        var measuringSwipe=false
        val scroll=ScrollView(this).apply {addView(column);setOnScrollChangeListener {_,_,_,_,_ -> if(measuringSwipe)scrollEvents++}}
        setContentView(scroll)
        val title=TextView(this).apply {setText(R.string.test_title);textSize=24f};column.addView(title)
        val counter=TextView(this).apply {text=getString(R.string.test_counter,0);textSize=20f;setPadding(0,dp(12),0,dp(12))};column.addView(counter)
        val target=Button(this).apply {setText(R.string.test_target);id=R.id.test_target;contentDescription=getString(R.string.test_target);setOnClickListener{count++;counter.text=getString(R.string.test_counter,count);app.store.log("sandbox","RECEIVED $count");android.util.Log.i("TapRunnerTest","RECEIVED $count")} }
        column.addView(target,LinearLayout.LayoutParams(-1,dp(120)))
        column.addView(TextView(this).apply {setText(R.string.test_description);textSize=13f;setPadding(0,dp(12),0,dp(12))})
        fun create(n:Int) {
            val script=Script(id=UUID.randomUUID().toString(),name="真机测试 $n 次",meta=Meta(minIntervalMs=150,maxLoopIterations=10000),steps=listOf(Step("ready","waitText",text="测试点击目标"),Step("repeat","loop",times=n,body=listOf("tap")),Step("tap","click",target=Target("id","cn.taprunner:id/test_target","equals"),holdMs=50),Step("verify","waitText",text="已接收点击：$n",mode="equals"),Step("done","stop",reason="$n 次测试完成")))
            app.store.save(script);count=0;counter.text=getString(R.string.test_counter,0);startForegroundService(Intent(this,RunnerService::class.java).setAction("run"))
        }
        column.addView(Button(this).apply {setText(R.string.test_run_small);setOnClickListener {runCatching{create(100)}.onFailure{Toast.makeText(this@TestActivity,it.message,Toast.LENGTH_LONG).show()}}})
        column.addView(Button(this).apply {setText(R.string.test_run_large);setOnClickListener{create(10000)}})
        column.addView(Button(this).apply {setText(R.string.test_run_swipe);setOnClickListener {
            runCatching {
                require(!app.overlayDraft.dirty){"请先保存或作废草稿"};scrollEvents=0;measuringSwipe=true
                val up=Step("up","swipe",from=listOf(.75,.8),to=listOf(.75,.35),durationMs=350)
                val down=Step("down","swipe",from=listOf(.75,.35),to=listOf(.75,.8),durationMs=350)
                val script=Script(id=UUID.randomUUID().toString(),name="真机滑动测试",meta=Meta(minIntervalMs=500),steps=listOf(Step("repeat","loop",times=5,body=listOf("up","down")),up,down))
                app.store.save(script);startForegroundService(Intent(this@TestActivity,RunnerService::class.java).setAction("run"))
            }.onFailure {measuringSwipe=false;Toast.makeText(this@TestActivity,it.message,Toast.LENGTH_LONG).show()}
        }})
        column.addView(Button(this).apply {setText(R.string.test_stop);setOnClickListener{app.engine.stop("测试台停止")}})
        column.addView(Button(this).apply {setText(R.string.test_back);setOnClickListener{finish()}})
        column.addView(TextView(this).apply {setText(R.string.test_scroll_title);textSize=18f;setPadding(0,dp(18),0,dp(8))})
        repeat(24){index -> column.addView(TextView(this).apply {text=getString(R.string.test_scroll_row,index+1);textSize=15f;setPadding(dp(12),dp(14),dp(12),dp(14));setBackgroundColor(if(index%2==0)palette.surface else palette.raised)})}
        tintNativeTree(column,palette)
        monitor=app.scope.launch {app.engine.state.collect {progress -> if(measuringSwipe&&progress.status in listOf(Status.FINISHED,Status.FAULT)){app.store.log("sandbox","SWIPE_SCROLL_EVENTS=$scrollEvents GESTURES=${progress.gestures} STATUS=${progress.status}");measuringSwipe=false}}}
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onDestroy(){monitor?.cancel();super.onDestroy()}
}
