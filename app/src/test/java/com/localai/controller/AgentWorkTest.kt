package com.localai.controller

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AgentWorkTest {
    private class FakeProcess : Process() {
        var destroyed = false
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun stopInvalidatesTicketsAndTerminatesAttachedCommand() = runBlocking {
        AgentWork.run { ticket ->
            val process = FakeProcess()
            ticket.attach(process)
            AgentWork.cancelAll()
            assertTrue(process.destroyed); assertTrue(AgentWork.stopping)
            assertThrows(AgentWork.Stopped::class.java) { ticket.check() }
        }
        assertFalse(AgentWork.active)
    }

    @Test fun commandSpawnRacingWithStopIsTerminated() = runBlocking {
        AgentWork.run { ticket ->
            AgentWork.cancelAll()
            val process = FakeProcess()
            assertThrows(AgentWork.Stopped::class.java) { ticket.attach(process) }
            assertTrue(process.destroyed)
        }
    }

    @Test fun newWorkAfterStopHasFreshGeneration() = runBlocking {
        val epoch = AgentWork.generation
        AgentWork.cancelAll()
        AgentWork.run { ticket ->
            assertTrue(ticket.epoch > epoch); ticket.check()
            assertTrue(AgentWork.active); assertFalse(AgentWork.stopping)
        }
        assertFalse(AgentWork.active)
    }

    @Test fun stoppedQueuedWorkCannotBeginWhenEarlierTaskFinishes() = runBlocking {
        AgentWork.run { first ->
            AgentWork.run { queued ->
                AgentWork.cancelAll()
                assertThrows(AgentWork.Stopped::class.java) { first.check() }
                assertThrows(AgentWork.Stopped::class.java) { queued.check() }
            }
        }
        assertFalse(AgentWork.active)
    }
}
