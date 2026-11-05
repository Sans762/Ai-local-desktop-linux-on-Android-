package com.localai.controller

import java.util.concurrent.CancellationException

/** Process-wide stop signal shared by chat, native desktop and local web tasks. */
object AgentWork {
    class Stopped : CancellationException("TASK_CANCELLED")

    class Ticket internal constructor(val epoch: Long) {
        @Volatile var cancelled = false
            private set
        private var process: Process? = null

        fun check() {
            if (cancelled || epoch != generation || Thread.currentThread().isInterrupted) throw Stopped()
        }

        @Synchronized fun attach(value: Process) {
            if (cancelled || epoch != generation) {
                value.destroyForcibly()
                throw Stopped()
            }
            process = value
        }

        @Synchronized fun detach(value: Process) {
            if (process === value) process = null
        }

        @Synchronized internal fun cancel() {
            cancelled = true
            try { process?.destroyForcibly() } catch (_: Exception) { }
        }
    }

    private val tickets = mutableSetOf<Ticket>()
    @Volatile var generation = 0L
        private set
    val active: Boolean get() = synchronized(tickets) { tickets.isNotEmpty() }
    val stopping: Boolean get() = synchronized(tickets) { tickets.any { it.cancelled } }

    fun cancelAll() {
        synchronized(tickets) {
            generation += 1 // Also invalidates plans waiting for approval.
            tickets.forEach { it.cancel() }
        }
        TaskCheckpointStore.cancelRegistered()
    }

    suspend fun <T> run(block: suspend (Ticket) -> T): T {
        val ticket = synchronized(tickets) { Ticket(generation).also { tickets.add(it) } }
        try {
            ticket.check()
            return block(ticket)
        } finally {
            synchronized(tickets) { tickets.remove(ticket) }
        }
    }
}
