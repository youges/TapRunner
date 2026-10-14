package cn.taprunner.engine

import kotlin.test.*

class DisplayProgressTest {
    @Test fun rapidCountsAreBoundedAndNewestCountCanRender() {
        val gate=DisplayProgressGate()
        val accepted=(0..1000 step 10).filter {gate.accept(Progress(Status.RUNNING,gestures=it/10),it.toLong())}
        assertEquals((0..1000 step 100).toList(),accepted)
    }
    @Test fun pauseStopAndFaultBypassCountThrottle() {
        val gate=DisplayProgressGate()
        assertTrue(gate.accept(Progress(Status.RUNNING),0))
        assertFalse(gate.accept(Progress(Status.RUNNING,gestures=1),1))
        assertTrue(gate.accept(Progress(Status.PAUSED),2))
        assertTrue(gate.accept(Progress(Status.RUNNING),3))
        assertTrue(gate.accept(Progress(Status.FAULT,message="断开"),4))
        assertTrue(gate.accept(Progress(Status.FINISHED,gestures=7),5))
    }
}
