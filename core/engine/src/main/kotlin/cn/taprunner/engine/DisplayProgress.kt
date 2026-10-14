package cn.taprunner.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Rendering can skip intermediate counts; execution and stop signals remain unthrottled. */
class DisplayProgressGate(private val intervalMs: Long = 100) {
    private var previous: Progress? = null
    private var lastAt = Long.MIN_VALUE
    fun accept(value: Progress, now: Long): Boolean {
        val last = previous
        val immediate = last == null || last.status != value.status || value.status != Status.RUNNING
        if (!immediate && now - lastAt < intervalMs) return false
        previous=value; lastAt=now
        return true
    }
}
fun Flow<Progress>.forDisplay(clock: () -> Long): Flow<Progress> = flow {
    val gate=DisplayProgressGate()
    collect { if(gate.accept(it,clock())) emit(it) }
}
