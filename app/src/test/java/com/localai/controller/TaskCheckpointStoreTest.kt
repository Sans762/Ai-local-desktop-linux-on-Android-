package com.localai.controller

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class TaskCheckpointStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun runningTask(): AgentTask = AgentTask("task-1", "สร้างไฟล์งานภาษาไทย").apply {
        startPlanning(); planned(); beginExecution()
        noteExecution("Actual result: file was written; verification was interrupted")
    }

    @Test fun interruptedExecutionRestoresEvidenceAndRequiresFreshApproval() {
        val folder = temporary.newFolder()
        TaskCheckpointStore(folder, "desktop").save(runningTask())
        val restored = TaskCheckpointStore(folder, "desktop").restore()!!
        assertEquals("สร้างไฟล์งานภาษาไทย", restored.request)
        assertEquals(1, restored.rounds)
        assertTrue(restored.evidence.contains("verification was interrupted"))
        assertEquals(AgentTask.State.AWAITING_CONTINUATION, restored.state)
        assertTrue(restored.recovered)
        assertFalse(restored.beginExecution())
        assertTrue(restored.startPlanning()); restored.planned()
        assertTrue(restored.beginExecution())
        assertEquals(2, restored.rounds)
    }

    @Test fun pendingApprovalIsNotRestoredAsPermissionToExecute() {
        val folder = temporary.newFolder()
        val task = AgentTask("pending", "create a file").apply { startPlanning(); planned() }
        TaskCheckpointStore(folder, "chat").save(task)
        val restored = TaskCheckpointStore(folder, "chat").restore()!!
        assertTrue(restored.canContinue())
        assertFalse(restored.beginExecution())
        val properties = File(folder, "chat-task.properties").readText()
        assertFalse(properties.contains("planId"))
        assertFalse(properties.contains("commands="))
    }

    @Test fun elapsedTimeIsChargedWhileProcessWasGone() {
        val folder = temporary.newFolder()
        var now = 1000L
        TaskCheckpointStore(folder, "chat") { now }.save(runningTask())
        now += 90_000L
        val remaining = TaskCheckpointStore(folder, "chat") { now }.load()!!.remainingMs
        assertTrue(remaining in 509_000L..510_000L)
        now += AgentTask.BUDGET_MS
        assertNull(TaskCheckpointStore(folder, "chat") { now }.restore())
    }

    @Test fun completedOrCancelledTaskDoesNotReappearAsResumable() {
        val folder = temporary.newFolder()
        val store = TaskCheckpointStore(folder, "chat")
        val task = runningTask()
        task.observed(true, true, true, false, "verified")
        store.save(task); assertNull(store.restore())
        task.cancel(); store.save(task); assertNull(store.restore())
    }

    @Test fun explicitStopIsPersisted() {
        val store = TaskCheckpointStore(temporary.newFolder(), "chat")
        store.save(runningTask())
        AgentWork.cancelAll()
        assertEquals(AgentTask.State.CANCELLED, store.load()!!.state)
        assertNull(store.restore())
    }

    @Test fun staleWriterCannotReplaceCheckpointAfterStop() {
        val store = TaskCheckpointStore(temporary.newFolder(), "chat")
        store.save(runningTask())
        AgentWork.cancelAll()
        assertFalse(store.save(runningTask()) { false })
        assertEquals(AgentTask.State.CANCELLED, store.load()!!.state)
    }

    @Test fun incompleteStagingFileDoesNotReplaceLastValidCheckpoint() {
        val folder = temporary.newFolder()
        val store = TaskCheckpointStore(folder, "desktop")
        store.save(runningTask())
        File(folder, "desktop-task.properties.new").writeText("interrupted write")
        assertEquals("task-1", TaskCheckpointStore(folder, "desktop").restore()!!.id)
        store.save(runningTask())
        assertFalse(File(folder, "desktop-task.properties.new").exists())
    }

    @Test fun malformedOrOversizedRecordIsIgnored() {
        val folder = temporary.newFolder()
        val file = File(folder, "chat-task.properties")
        file.writeText("schema=999")
        assertNull(TaskCheckpointStore(folder, "chat").load())
        file.writeText("x".repeat(70_000))
        assertNull(TaskCheckpointStore(folder, "chat").load())
    }

    @Test fun invalidRoundCountCannotResetTheBudget() {
        val folder = temporary.newFolder()
        val store = TaskCheckpointStore(folder, "desktop")
        store.save(runningTask())
        val file = File(folder, "desktop-task.properties")
        val p = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        p.setProperty("rounds", "100")
        file.writer(Charsets.UTF_8).use { p.store(it, "") }
        assertNull(store.restore())
    }

    @Test fun nativeDesktopAndChatKeepSeparateBoundedRecords() {
        val folder = temporary.newFolder()
        val desktop = TaskCheckpointStore(folder, "desktop")
        val chat = TaskCheckpointStore(folder, "chat")
        desktop.save(runningTask())
        chat.save(AgentTask("chat-1", "another task"))
        assertEquals("task-1", desktop.load()!!.id)
        assertEquals("chat-1", chat.load()!!.id)
        assertEquals(2, folder.listFiles()!!.size)
        assertTrue(folder.listFiles()!!.all { it.length() <= 65_536L })
        assertThrows(IllegalArgumentException::class.java) { TaskCheckpointStore(folder, "../other") }
    }
}
