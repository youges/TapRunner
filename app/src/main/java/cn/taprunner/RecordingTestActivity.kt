package cn.taprunner

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity

/** A local two-page target: the second button only exists after the page transition. */
class RecordingTestActivity:ComponentActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var entered=0
    private var confirmed=0
    private var returned=0
    override fun onCreate(savedInstanceState:Bundle?) {
        val app=application as TapApp
        setTheme(if(app.appearance.palette(this).dark)R.style.Theme_TapRunner_Dark else R.style.Theme_TapRunner_Light)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showOrigin()
    }
    private fun column(title:Int):LinearLayout {
        val palette=TapApp.instance.appearance.palette(this)
        val density=resources.displayMetrics.density
        val column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding((24*density).toInt(),(48*density).toInt(),(24*density).toInt(),(24*density).toInt());setBackgroundColor(palette.background)}
        column.addView(TextView(this).apply {setText(title);textSize=23f})
        column.addView(TextView(this).apply {text=getString(R.string.record_test_counts,entered,confirmed,returned);textSize=16f;setPadding(0,(16*density).toInt(),0,(16*density).toInt())})
        setContentView(column)
        return column
    }
    private fun button(column:LinearLayout,label:Int,action:()->Unit) {
        column.addView(Button(this).apply {setText(label);setOnClickListener {action()}},LinearLayout.LayoutParams(-1,(64*resources.displayMetrics.density).toInt()))
    }
    private fun showOrigin() {
        val root=column(R.string.record_test_origin)
        root.addView(TextView(this).apply {setText(R.string.record_test_instruction);textSize=14f})
        button(root,R.string.record_test_enter){entered++;TapApp.instance.store.log("record-test","ENTER=$entered");handler.postDelayed({showDetail()},700)}
        button(root,R.string.record_test_reset){entered=0;confirmed=0;returned=0;handler.removeCallbacksAndMessages(null);showOrigin()}
        button(root,R.string.test_back){finish()}
        tintNativeTree(root,TapApp.instance.appearance.palette(this))
    }
    private fun showDetail() {
        val root=column(R.string.record_test_detail)
        root.addView(TextView(this).apply {setText(R.string.record_test_detail_hint);textSize=14f;setPadding(0,(80*resources.displayMetrics.density).toInt(),0,0)})
        val result=TextView(this).apply {textSize=17f;setText(R.string.record_test_not_confirmed)}
        button(root,R.string.record_test_confirm){confirmed++;result.text=getString(R.string.record_test_confirmed,confirmed);TapApp.instance.store.log("record-test","DETAIL=$confirmed")}
        root.addView(result)
        button(root,R.string.record_test_return){returned++;TapApp.instance.store.log("record-test","RETURN=$returned");showOrigin()}
        tintNativeTree(root,TapApp.instance.appearance.palette(this))
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
