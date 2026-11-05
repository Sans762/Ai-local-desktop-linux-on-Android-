package com.localai.controller

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class AgentTaskTest {
    private fun executing(): AgentTask = AgentTask("id", "create a file").apply {
        assertTrue(startPlanning()); planned(); assertTrue(beginExecution())
    }

    @Test fun zeroExitWithoutEvidenceDoesNotCompleteTask() {
        val task = executing()
        task.observed(true, true, false, false, "[exit 0]")
        assertEquals(AgentTask.State.AWAITING_CONTINUATION, task.state)
        assertTrue(task.canContinue())
    }

    @Test fun verifiedFileCanCompleteNonGraphicalTask() {
        val task = executing()
        task.observed(true, true, true, false, "file contains expected text")
        assertEquals(AgentTask.State.VERIFIED, task.state)
        assertFalse(task.canContinue())
        assertFalse(task.startPlanning())
    }

    @Test fun fileCheckDoesNotProveGuiSuccess() {
        val task = executing()
        task.observed(true, true, true, true, "xdotool click returned 0")
        assertNotEquals(AgentTask.State.VERIFIED, task.state)
    }

    @Test fun failedCommandOrFileCheckCannotCompleteTask() {
        val commandFailure = executing()
        commandFailure.observed(false, true, true, false, "permission denied")
        val fileFailure = executing()
        fileFailure.observed(true, false, true, false, "missing text")
        assertTrue(commandFailure.canContinue())
        assertTrue(fileFailure.canContinue())
    }

    @Test fun repeatedFailureStopsAtRoundLimit() {
        val task = AgentTask("id", "task")
        repeat(AgentTask.MAX_ROUNDS) {
            assertTrue(task.startPlanning()); task.planned(); assertTrue(task.beginExecution())
            task.observed(false, false, false, false, "failure")
        }
        assertEquals(AgentTask.State.LIMIT_REACHED, task.state)
        assertFalse(task.canContinue()); assertFalse(task.startPlanning())
    }

    @Test fun approvalAfterDeadlineCannotExecute() {
        var now = 0L
        val task = AgentTask("id", "task") { now }
        task.startPlanning(); task.planned()
        now = AgentTask.BUDGET_MS
        assertFalse(task.beginExecution())
        assertEquals(AgentTask.State.LIMIT_REACHED, task.state)
    }

    @Test fun cancellationPreventsResuming() {
        val task = executing()
        task.cancel()
        assertFalse(task.startPlanning()); assertFalse(task.beginExecution()); assertFalse(task.canContinue())
    }

    @Test fun evidenceRetentionIsBounded() {
        val task = executing()
        task.observed(false, false, false, false, "x".repeat(20_000))
        assertEquals(6000, task.evidence.length)
    }

    @Test fun pathsCannotEscapeApprovedFileRoots() {
        for (path in listOf("/proc/1/mem", "/dev/zero", "/root/../etc/passwd", "/home//file", "/root/a\ncat")) {
            assertThrows(IllegalArgumentException::class.java) { AgentFileCheck(path, "text") }
        }
        assertThrows(IllegalArgumentException::class.java) { AgentFileCheck("/root/a", "") }
    }

    @Test fun expectedTextAndRealExitStatusAreRequired() {
        val check = AgentFileCheck("/root/result.txt", "hello")
        assertTrue(check.matches("hello\n[exit 0]"))
        assertFalse(check.matches("wrong\n[exit 0]"))
        assertFalse(check.matches("hello\n[exit 0]\n[exit 1]"))
        assertFalse(check.matches("hello\n[timeout: process stopped]"))
    }

    @Test fun verifierReadsRealFileAndDoesNotExecuteShellMetacharacters() {
        assumeFalse(System.getProperty("os.name", "").orEmpty().lowercase().contains("windows"))
        val folder = Files.createTempDirectory(Paths.get(System.getProperty("user.home")), "localai-check-")
        try {
            val path = folder.resolve("quote'\$(touch injected).txt")
            Files.write(path, "actual evidence".toByteArray())
            val check = AgentFileCheck(path.toString(), "actual evidence")
            val process = ProcessBuilder("sh", "-c", check.command()).directory(folder.toFile()).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor()); assertTrue(check.matches(output + "\n[exit 0]"))
            assertFalse(Files.exists(folder.resolve("injected")))
        } finally { folder.toFile().deleteRecursively() }
    }

    @Test fun verifierRefusesSymlinkOutsideFileRoots() {
        assumeFalse(System.getProperty("os.name", "").orEmpty().lowercase().contains("windows"))
        val folder = Files.createTempDirectory(Paths.get(System.getProperty("user.home")), "localai-check-")
        try {
            val link = Files.createSymbolicLink(folder.resolve("passwd"), Paths.get("/etc/passwd"))
            val process = ProcessBuilder("sh", "-c", AgentFileCheck(link.toString(), null).command()).start()
            assertNotEquals(0, process.waitFor())
        } finally { folder.toFile().deleteRecursively() }
    }
}
