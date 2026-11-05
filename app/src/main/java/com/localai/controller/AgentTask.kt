package com.localai.controller

/** Bounded task progression; no Android or model dependency, so it can be tested on the JVM. */
class AgentTask(
    val id: String,
    val request: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    companion object {
        const val MAX_ROUNDS = 4
        const val BUDGET_MS = 10L * 60_000L

        /** Recovery never restores approval or starts commands. It requires a new plan. */
        fun restore(id: String, request: String, rounds: Int, evidence: String,
                    remainingMs: Long, oldState: State,
                    clock: () -> Long = { System.nanoTime() / 1_000_000L }): AgentTask? {
            if (rounds !in 0 until MAX_ROUNDS || remainingMs !in 1..BUDGET_MS ||
                oldState !in setOf(State.PLANNING, State.AWAITING_APPROVAL,
                    State.RUNNING, State.AWAITING_CONTINUATION)) return null
            return AgentTask(id, request, clock).apply {
                this.rounds = rounds
                this.evidence = evidence.takeLast(6000)
                startedAt = clock() - (BUDGET_MS - remainingMs)
                state = State.AWAITING_CONTINUATION
                recovered = true
            }
        }
    }
    enum class State { PLANNING, AWAITING_APPROVAL, RUNNING, AWAITING_CONTINUATION,
        NEEDS_INPUT, VERIFIED, CANCELLED, LIMIT_REACHED }

    private var startedAt = clock()
    var rounds = 0
        private set
    var state = State.PLANNING
        private set
    var evidence = ""
        private set
    var recovered = false
        private set

    fun remainingMillis(): Long = (BUDGET_MS - (clock() - startedAt).coerceAtLeast(0L))
        .coerceIn(0L, BUDGET_MS)
    fun timeAvailable(): Boolean = remainingMillis() > 0
    fun budgetAvailable(): Boolean = rounds < MAX_ROUNDS && timeAvailable()

    fun startPlanning(): Boolean {
        if (state in setOf(State.VERIFIED, State.CANCELLED, State.NEEDS_INPUT)) return false
        if (!budgetAvailable()) { state = State.LIMIT_REACHED; return false }
        state = State.PLANNING
        recovered = false
        return true
    }

    fun planned(needsInput: Boolean = false) {
        check(state == State.PLANNING)
        state = if (needsInput) State.NEEDS_INPUT else State.AWAITING_APPROVAL
    }

    fun beginExecution(): Boolean {
        if (state != State.AWAITING_APPROVAL) return false
        if (!budgetAvailable()) { state = State.LIMIT_REACHED; return false }
        rounds += 1
        state = State.RUNNING
        return true
    }

    fun observed(commandsOk: Boolean, checksPassed: Boolean, hasChecks: Boolean,
                 graphical: Boolean, output: String) {
        check(state == State.RUNNING)
        evidence = output.takeLast(6000)
        state = when {
            commandsOk && checksPassed && hasChecks && !graphical -> State.VERIFIED
            !budgetAvailable() -> State.LIMIT_REACHED
            else -> State.AWAITING_CONTINUATION
        }
    }

    fun cancel() { state = State.CANCELLED }
    fun noteExecution(output: String) {
        check(state == State.RUNNING)
        evidence = output.takeLast(6000)
    }
    fun canContinue(): Boolean = state == State.AWAITING_CONTINUATION && budgetAvailable()
}

/** A typed, read-only file check. Neither a path nor expected text becomes executable code. */
data class AgentFileCheck(val path: String, val contains: String?) {
    init {
        require(path.length in 7..240 && (path.startsWith("/root/") || path.startsWith("/home/")))
        require(path.none { it.isISOControl() } && path.split('/').drop(1).none { it in setOf("", ".", "..") })
        require(contains == null || contains.length in 1..800)
    }

    fun command(): String {
        val quoted = "'" + path.replace("'", "'\"'\"'") + "'"
        // Resolve symlinks inside Alpine and refuse /proc, /dev, and other roots.
        return "p=\$(readlink -f -- $quoted) || exit 1; " +
            "case \"\$p\" in /root/*|/home/*) ;; *) exit 1;; esac; " +
            "test -f \"\$p\" && test -r \"\$p\" && " +
            "test \"\$(wc -c < \"\$p\")\" -le 65536 && cat \"\$p\""
    }

    fun matches(output: String): Boolean {
        if (!output.trimEnd().endsWith("[exit 0]")) return false
        val content = output.trimEnd().removeSuffix("[exit 0]").removeSuffix("\n")
        return contains == null || content.contains(contains)
    }
}
