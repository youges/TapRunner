package cn.taprunner.engine

import cn.taprunner.model.*
import cn.taprunner.model.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {
    class Fake(private val clock:()->Long={0L}): Runtime {
        var taps=0;var finds=0;var hit=true;var success=true;var fault:String?=null;var active=0;var maxActive=0
        val logs=mutableListOf<String>();var point:Point?=null
        val starts=mutableListOf<Long>()
        override suspend fun gesture(from:Point,to:Point?,duration:Long):Boolean {starts+=clock();active++;maxActive=maxOf(maxActive,active);delay(duration);active--;taps++;point=from;return success}
        override suspend fun find(target:Target):Point? {finds++;return if(hit)Point(.2,.3)else null}
        override suspend fun key(name:String)=name in listOf("back","home","recents")
        override suspend fun input(target:Target,text:String)=hit
        override suspend fun launch(pkg:String)=pkg.isNotBlank()
        override suspend fun image(step:Step)=hit
        override fun log(stepId:String,result:String){logs+="$stepId:$result"}
        override fun healthy(expectedPkg:String)=fault
        override fun now()=clock()
    }
    private fun script(vararg steps:Step)=Script(id="test",steps=steps.toList())
    @Test fun oneTap()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a")));advanceUntilIdle();assertEquals(1,f.taps);assertEquals(Status.FINISHED,e.state.value.status)}
    @Test fun sequentialCallbacks()=runTest{val f=Fake();val e=Engine(f,this);e.start(Script(id="x",steps=(1..20).map{Step("s$it")}));advanceUntilIdle();assertEquals(20,f.taps);assertEquals(1,f.maxActive)}
    @Test fun loopDoesNotReplayChildren()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("loop","loop",times=20,body=listOf("tap")),Step("tap")));advanceUntilIdle();assertEquals(20,f.taps)}
    @Test fun nestedLoop()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","loop",times=2,body=listOf("b")),Step("b","loop",times=3,body=listOf("c")),Step("c")));advanceUntilIdle();assertEquals(6,f.taps)}
    @Test fun totalLoopBudget()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","loop",times=2,body=listOf("b")),Step("b","loop",times=3,body=listOf("c")),Step("c")).copy(meta=Meta(maxLoopIterations=4)));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status)}
    @Test fun emptyRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script())}}
    @Test fun zeroLoopRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","loop",times=0,body=listOf("b")),Step("b")))}}
    @Test fun missingChildRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","loop",body=listOf("missing"))))}}
    @Test fun recursiveLoopRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","loop",body=listOf("a"))))}}
    @Test fun duplicateRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a"),Step("a")))}}
    @Test fun coordinatesRejected(){listOf(-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY).forEach{assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a",x=it)))}}}
    @Test fun negativeDurationRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","delay",ms=-1)))}}
    @Test fun tooFastRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a")).copy(meta=Meta(minIntervalMs=0)))}}
    @Test fun schemaRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a")).copy(schema=2))}}
    @Test fun unknownStepRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","shell")))}}
    @Test fun pauseAndResume()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","delay",ms=1000),Step("b")));runCurrent();e.pause();advanceTimeBy(2000);assertEquals(0,f.taps);assertEquals(Status.PAUSED,e.state.value.status);e.resume();advanceUntilIdle();assertEquals(1,f.taps)}
    @Test fun stopDuringDelay()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","delay",ms=5000),Step("b")));runCurrent();e.stop("urgent");advanceUntilIdle();assertEquals(0,f.taps);assertEquals("urgent",e.state.value.message)}
    @Test fun stopDuringGesture()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a",holdMs=1000),Step("b")));runCurrent();e.stop();advanceUntilIdle();assertEquals(0,f.taps)}
    @Test fun lockCannotBeSkipped()=runTest{val f=Fake();f.fault="locked";val e=Engine(f,this);e.start(script(Step("a",onError=JsonPrimitive("skip"))));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status);assertEquals(0,f.taps)}
    @Test fun gestureFailure()=runTest{val f=Fake();f.success=false;val e=Engine(f,this);e.start(script(Step("a")));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status);assertEquals(0,e.state.value.gestures)}
    @Test fun skipFailure()=runTest{val f=Fake();f.success=false;val e=Engine(f,this);e.start(script(Step("a",onError=JsonPrimitive("skip")),Step("b","log",message="continued")));advanceUntilIdle();assertEquals(Status.FINISHED,e.state.value.status);assertTrue(f.logs.any{it.contains("continued")})}
    @Test fun retryBounded()=runTest{val f=Fake();f.success=false;val e=Engine(f,this);e.start(script(Step("a",onError=Json.parseToJsonElement("{\"retry\":{\"times\":3,\"backoffMs\":100}}"))));advanceUntilIdle();assertEquals(4,f.taps);assertEquals(Status.FAULT,e.state.value.status)}
    @Test fun waitTimeout()=runTest{val f=Fake();f.hit=false;val e=Engine(f,this);e.start(script(Step("a","waitText",text="missing",timeoutMs=1000)));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status);assertTrue(f.finds>=3)}
    @Test fun waitSuccess()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","waitText",text="found")));advanceUntilIdle();assertEquals(1,f.finds);assertEquals(Status.FINISHED,e.state.value.status)}
    @Test fun nodeBeforeCoordinates()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a",target=Target(value="found"),fallback=Point(.8,.9))));advanceUntilIdle();assertEquals(Point(.2,.3),f.point)}
    @Test fun fallbackCoordinates()=runTest{val f=Fake();f.hit=false;val e=Engine(f,this);e.start(script(Step("a",target=Target(value="missing"),fallback=Point(.8,.9))));advanceUntilIdle();assertEquals(Point(.8,.9),f.point)}
    @Test fun absentTargetWithoutFallbackFails()=runTest{val f=Fake();f.hit=false;val e=Engine(f,this);e.start(script(Step("a",target=Target(value="missing"))));advanceUntilIdle();assertEquals(0,f.taps);assertEquals(Status.FAULT,e.state.value.status)}
    @Test fun trueBranch()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","if",condition=Target(value="x"),then=listOf("b"),otherwise=listOf("c")),Step("b"),Step("c","log",message="bad")));advanceUntilIdle();assertEquals(1,f.taps);assertFalse(f.logs.any{it.contains("bad")})}
    @Test fun falseBranch()=runTest{val f=Fake();f.hit=false;val e=Engine(f,this);e.start(script(Step("a","if",condition=Target(value="x"),then=listOf("b"),otherwise=listOf("c")),Step("b"),Step("c","log",message="else")));advanceUntilIdle();assertEquals(0,f.taps);assertTrue(f.logs.any{it.contains("else")})}
    @Test fun gotoMissingRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a",onError=Json.parseToJsonElement("{\"goto\":\"missing\"}"))))}}
    @Test fun gotoCycleRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a",onError=Json.parseToJsonElement("{\"goto\":\"b\"}")),Step("b",onError=Json.parseToJsonElement("{\"goto\":\"a\"}"))))}}
    @Test fun stopStepEndsSequence()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","stop",reason="done"),Step("b")));advanceUntilIdle();assertEquals(0,f.taps);assertEquals("done",e.state.value.message)}
    @Test fun interpolateVariables()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","log",message="Hello {{name}} {{round}}")).copy(vars=mapOf("name" to "test")));advanceUntilIdle();assertTrue(f.logs.any{it.contains("Hello test 0")})}
    @Test fun swipeCompletes()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a","swipe")));advanceUntilIdle();assertEquals(1,e.state.value.gestures)}
    @Test fun malformedSwipeRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","swipe",from=listOf(.5))))}}
    @Test fun keySuccess()=runTest{val e=Engine(Fake(),this);e.start(script(Step("a","key",key="back")));advanceUntilIdle();assertEquals(Status.FINISHED,e.state.value.status)}
    @Test fun unsupportedKeyFaults()=runTest{val e=Engine(Fake(),this);e.start(script(Step("a","key",key="volumeUp")));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status)}
    @Test fun launchFailure()=runTest{val e=Engine(Fake(),this);e.start(script(Step("a","launchApp",pkg="")));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status)}
    @Test fun missingInputTargetRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a","inputText")))}}
    @Test fun concurrentStartRejected()=runTest{val e=Engine(Fake(),this);e.start(script(Step("a","delay",ms=500)));assertFailsWith<IllegalStateException>{e.start(script(Step("b")))};advanceUntilIdle()}
    @Test fun finishedCanRestart()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a")));advanceUntilIdle();e.start(script(Step("b")));advanceUntilIdle();assertEquals(2,f.taps);assertEquals(1,e.state.value.gestures)}
    @Test fun repeatWholeSequence()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("a"),Step("b")).copy(meta=Meta(repeatCount=3)));advanceUntilIdle();assertEquals(6,f.taps);assertEquals(2,e.state.value.round)}
    @Test fun zeroRepeatRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a")).copy(meta=Meta(repeatCount=0)))}}
    @Test fun repetitionUsesGlobalBudget()=runTest{val f=Fake();val e=Engine(f,this);e.start(script(Step("loop","loop",times=2,body=listOf("tap")),Step("tap")).copy(meta=Meta(repeatCount=3,maxLoopIterations=4)));advanceUntilIdle();assertEquals(Status.FAULT,e.state.value.status)}
    @Test fun pointIntervalOverridesDefault()=runTest{val f=Fake{testScheduler.currentTime};val e=Engine(f,this);e.start(script(Step("a",intervalMs=500),Step("b")).copy(meta=Meta(minIntervalMs=150)));advanceUntilIdle();assertEquals(listOf(0L,500L),f.starts)}
    @Test fun clickStartsAreTwoHundredMsApartAcrossRounds()=runTest {
        val f=Fake{testScheduler.currentTime};val e=Engine(f,this)
        e.start(script(Step("a"),Step("b"),Step("c")).copy(meta=Meta(minIntervalMs=200,repeatCount=2)))
        advanceUntilIdle();assertEquals(listOf(0L,200L,400L,600L,800L,1000L),f.starts);assertEquals(1,f.maxActive)
    }
    @Test fun eachPointControlsDelayToFollowingPoint()=runTest {
        val f=Fake{testScheduler.currentTime};val e=Engine(f,this)
        e.start(script(Step("a",intervalMs=200),Step("b",intervalMs=350),Step("c")))
        advanceUntilIdle();assertEquals(listOf(0L,200L,550L),f.starts)
    }
    @Test fun slowGestureDoesNotOverlapOrAddAnotherFullInterval()=runTest {
        val f=Fake{testScheduler.currentTime};val e=Engine(f,this)
        e.start(script(Step("a",holdMs=300),Step("b"),Step("c")).copy(meta=Meta(minIntervalMs=200)))
        advanceUntilIdle();assertEquals(listOf(0L,300L,500L),f.starts);assertEquals(1,f.maxActive)
    }
    @Test fun invalidPointIntervalRejected(){assertFailsWith<IllegalArgumentException>{Validator.validate(script(Step("a",intervalMs=0)))}}
}
