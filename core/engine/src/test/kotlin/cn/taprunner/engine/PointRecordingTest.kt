package cn.taprunner.engine

import cn.taprunner.model.Meta
import cn.taprunner.model.Point
import cn.taprunner.model.Script
import cn.taprunner.model.Step
import cn.taprunner.model.Target
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PointRecordingTest {
    private fun script(vararg steps: Step) = Script(id = "recording", steps = steps.toList())

    @Test fun recordsIntoEmptyScriptWithoutChangingMetadata() {
        val original = script().copy(name = "Existing", vars = mapOf("name" to "value"), meta = Meta(minIntervalMs = 300))
        val click = Step("first", x = .2, y = .4)
        assertEquals(original.copy(steps = listOf(click)), PointRecording.append(original, click))
    }

    @Test fun appendsMultiplePointsInOrder() {
        val first = PointRecording.append(script(), Step("first"))
        val second = PointRecording.append(first, Step("second"))
        assertEquals(listOf("first", "second", "third"), PointRecording.append(second, Step("third")).steps.map { it.id })
    }

    @Test fun insertsBeforeTrailingRootStop() {
        val stop = Step("stop", "stop", reason = "Done")
        val original = script(Step("first"), stop)
        assertEquals(listOf(original.steps.first(), Step("second"), stop), PointRecording.append(original, Step("second")).steps)
    }

    @Test fun ownedStopIsNotUsedAsInsertionPoint() {
        val original = script(Step("loop", "loop", body = listOf("stop")), Step("stop", "stop"))
        assertEquals(original.steps + Step("new"), PointRecording.append(original, Step("new")).steps)
    }

    @Test fun rejectsIntermediateOrMultipleRootStops() {
        for (original in listOf(script(Step("stop", "stop"), Step("existing")), script(Step("stop", "stop"), Step("stop2", "stop")))) {
            assertFailsWith<IllegalArgumentException> { PointRecording.append(original, Step("new")) }
        }
    }

    @Test fun updatesOnlyPreviousRecordedInterval() {
        val original = script(Step("older", intervalMs = 900), Step("previous", note = "Keep", x = .3), Step("stop", "stop"))
        val result = PointRecording.append(original, Step("new"), "previous", 1400)
        assertEquals(listOf(original.steps[0], original.steps[1].copy(intervalMs = 1400), Step("new"), original.steps[2]), result.steps)
    }

    @Test fun clampsShortRecordedInterval() {
        for (elapsed in listOf(-1L, 0L, 49L)) {
            assertEquals(50L, PointRecording.append(script(Step("previous")), Step("new"), "previous", elapsed).steps.first().intervalMs)
        }
    }

    @Test fun preservesLongWaitImmediatelyAfterPreviousClick() {
        val original = script(Step("previous"), Step("stop", "stop"))
        val result = PointRecording.append(original, Step("new"), "previous", 75000)
        assertEquals(listOf("click", "delay", "click", "stop"), result.steps.map { it.type })
        assertEquals(60000L, result.steps.first().intervalMs)
        assertEquals(15000L, result.steps[1].ms)
        assertTrue(result.steps[1].id.isNotBlank())
        assertEquals(4, result.steps.map { it.id }.toSet().size)
        assertEquals(original.steps.last(), result.steps.last())
    }

    @Test fun rejectsWaitLongerThanDelayLimitWithoutMutatingOriginal() {
        val original = script(Step("previous"))
        assertFailsWith<IllegalArgumentException> { PointRecording.append(original, Step("new"), "previous", 86460001) }
        assertNull(original.steps.first().intervalMs)
    }

    @Test fun acceptsMaximumRepresentableWait() {
        val result = PointRecording.append(script(Step("previous")), Step("new"), "previous", 86460000)
        assertEquals(86400000L, result.steps[1].ms)
    }

    @Test fun missingPreviousPointIsRejectedEvenWithoutInterval() {
        assertFailsWith<IllegalArgumentException> { PointRecording.append(script(Step("a")), Step("new"), "missing") }
    }

    @Test fun refusesIntervalWithoutPreviousOrPreviousChildPoint() {
        assertFailsWith<IllegalArgumentException> { PointRecording.append(script(), Step("new"), elapsedMs = 100) }
        assertFailsWith<IllegalArgumentException> {
            PointRecording.append(script(Step("loop", "loop", body = listOf("child")), Step("child")), Step("new"), "child", 100)
        }
    }

    @Test fun rejectsDuplicateOrInvalidNewPoint() {
        assertFailsWith<IllegalArgumentException> { PointRecording.append(script(Step("a")), Step("a")) }
        assertFailsWith<IllegalArgumentException> { PointRecording.append(script(), Step("a", x = 2.0)) }
        assertFailsWith<IllegalArgumentException> { PointRecording.append(script(), Step("a", "delay")) }
    }

    @Test fun staleClickReferencesDoNotOwnStop() {
        val original = script(Step("first", body = listOf("stop"), then = listOf("missing")), Step("stop", "stop"))
        assertEquals(listOf("first", "new", "stop"), PointRecording.append(original, Step("new")).steps.map { it.id })
    }

    @Test fun recordedCoordinatesAndIntervalsExecuteInOrder() = runTest {
        var recorded = PointRecording.append(script(Step("stop", "stop")), Step("one", x = .1, y = .2))
        recorded = PointRecording.append(recorded, Step("two", x = .3, y = .4), "one", 1400)
        recorded = PointRecording.append(recorded, Step("three", x = .5, y = .6), "two", 75000)
        val fake = EngineTest.Fake { testScheduler.currentTime }
        val coordinates = mutableListOf<Point>()
        val runtime = object : Runtime by fake {
            override suspend fun gesture(from: Point, to: Point?, duration: Long): Boolean {
                coordinates += from
                return fake.gesture(from, to, duration)
            }
        }
        val engine = Engine(runtime, this)
        engine.start(recorded)
        advanceUntilIdle()
        assertEquals(listOf(Point(.1, .2), Point(.3, .4), Point(.5, .6)), coordinates)
        assertEquals(listOf(0L, 1400L, 76400L), fake.starts)
        assertEquals(Status.FINISHED, engine.state.value.status)
    }

    @Test fun irrelevantReferencesDoNotHideClicksOrRejectLegacyScript() = runTest {
        val fake = EngineTest.Fake()
        val engine = Engine(fake, this)
        engine.start(script(Step("one", body = listOf("two"), then = listOf("missing"), otherwise = listOf("one")), Step("two")))
        advanceUntilIdle()
        assertEquals(2, fake.taps)
        assertEquals(Status.FINISHED, engine.state.value.status)
    }

    @Test fun loopAndBranchUseOnlyTheirRelevantChildren() = runTest {
        val fake = EngineTest.Fake()
        val engine = Engine(fake, this)
        engine.start(script(
            Step("loop", "loop", times = 2, body = listOf("loopClick"), then = listOf("rootClick"), otherwise = listOf("missing")),
            Step("loopClick"),
            Step("branch", "if", condition = Target(value = "yes"), body = listOf("rootClick"), then = listOf("yesClick"), otherwise = listOf("noClick")),
            Step("yesClick"), Step("noClick"), Step("rootClick")
        ))
        advanceUntilIdle()
        assertEquals(4, fake.taps)
        assertEquals(1, fake.logs.count { it == "rootClick:OK" })
    }

    @Test fun relevantBranchReferencesStillRejectMissingAndCycles() {
        assertFailsWith<IllegalArgumentException> { Validator.validate(script(Step("if", "if", condition = Target(value = "yes"), then = listOf("missing")))) }
        assertFailsWith<IllegalArgumentException> {
            Validator.validate(script(Step("loop", "loop", body = listOf("if")), Step("if", "if", condition = Target(value = "yes"), otherwise = listOf("loop"))))
        }
    }

    @Test fun nestingLimitCannotBeBypassedByChildFirstOrdering() {
        val steps = listOf(Step("leaf")) + (1..6).map { depth ->
            Step("loop$depth", "loop", body = listOf(if (depth == 1) "leaf" else "loop${depth - 1}"))
        }
        Validator.validate(script(*steps.dropLast(1).toTypedArray()))
        assertFailsWith<IllegalArgumentException> { Validator.validate(script(*steps.toTypedArray())) }
    }

    @Test fun swipeAndLongPressRecordingPreservesGestureOrderAndTiming() = runTest {
        val swipe = Step("scroll", "swipe", from = listOf(.2, .8), to = listOf(.2, .3), durationMs = 350)
        var recorded = PointRecording.append(script(), swipe)
        recorded = PointRecording.append(recorded, Step("hold", "longPress", x = .4, y = .5, holdMs = 700), "scroll", 1200)
        recorded = PointRecording.append(recorded, Step("click", x = .6, y = .7), "hold", 1800)
        val fake = EngineTest.Fake { testScheduler.currentTime }
        val destinations = mutableListOf<Point?>()
        val runtime = object : Runtime by fake {
            override suspend fun gesture(from: Point, to: Point?, duration: Long): Boolean {
                destinations += to
                return fake.gesture(from, to, duration)
            }
        }
        val engine = Engine(runtime, this)
        engine.start(recorded)
        advanceUntilIdle()
        assertEquals(listOf(Point(.2, .3), null, null), destinations)
        assertEquals(listOf(0L, 1200L, 3000L), fake.starts)
        assertEquals(Status.FINISHED, engine.state.value.status)
    }
}
