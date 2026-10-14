package cn.taprunner.engine

import cn.taprunner.model.Script
import cn.taprunner.model.Step
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRecoveryTest {
    @Test fun idleReconnectDoesNotCreateFault() = runTest {
        val engine = Engine(EngineTest.Fake(), this)
        engine.accessibilityUnavailable("断开")
        engine.accessibilityConnected()
        assertEquals(Status.IDLE, engine.state.value.status)
        assertEquals("无障碍已连接", engine.state.value.message)
    }
    @Test fun completedRunSurvivesIdleServiceReconnect() = runTest {
        val runtime = EngineTest.Fake()
        val engine = Engine(runtime, this)
        engine.start(Script(id="test", steps=listOf(Step("tap"))))
        advanceUntilIdle()
        val completed = engine.state.value
        engine.accessibilityUnavailable("断开")
        engine.accessibilityConnected()
        assertEquals(completed, engine.state.value)
        assertEquals(1, runtime.taps)
    }
    @Test fun reconnectAcknowledgesLossWithoutResumingInterruptedRun() = runTest {
        val runtime = EngineTest.Fake()
        val engine = Engine(runtime, this)
        engine.start(Script(id="test", steps=listOf(Step("wait", "delay", ms=5000), Step("tap"))))
        runCurrent()
        engine.pause()
        engine.accessibilityUnavailable("断开")
        engine.accessibilityConnected()
        engine.resume()
        advanceUntilIdle()
        assertEquals(Status.FAULT, engine.state.value.status)
        assertEquals(FaultCause.ACCESSIBILITY, engine.state.value.faultCause)
        assertTrue(engine.state.value.message.contains("已重新连接"))
        assertEquals(0, runtime.taps)
    }
    @Test fun reconnectPreservesOtherFaults() = runTest {
        val engine = Engine(EngineTest.Fake(), this)
        engine.stop("等待文字超时", true)
        val fault = engine.state.value
        engine.accessibilityUnavailable("断开")
        engine.accessibilityConnected()
        assertEquals(fault, engine.state.value)
    }
}
