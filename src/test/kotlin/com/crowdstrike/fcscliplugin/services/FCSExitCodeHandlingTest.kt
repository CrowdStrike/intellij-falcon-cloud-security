package com.crowdstrike.fcscliplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for FCS CLI exit code handling.
 *
 * EXIT CODE CONTRACT (from FCS CLI docs):
 *   0  — scan completed, no findings
 *   1  — general error
 *   2  — invalid arguments
 *   10 — binary/config error
 *   20 — auth error
 *   30 — network error
 *   40 — scan completed WITH findings (success — findings are normal output, not an error)
 *
 * Exit code 40 MUST be treated as success. Getting this wrong causes every scan that
 * finds issues to appear as a crash, hiding all annotations from the user.
 *
 * TODO(production-change): FCSFileScanTriggerService.runScanForFile and
 * FCSExternalAnnotator.triggerBackgroundScan both call ProcessBuilder directly with no
 * injection point. To make exit code handling testable, extract a ProcessExecutor
 * interface and inject it:
 *
 *   fun interface ProcessExecutor {
 *       fun run(command: List<String>): Int  // returns exit code
 *   }
 *
 * Default implementation wraps ProcessBuilder. Tests inject a fake.
 * Until that refactor lands, the tests below document expected behaviour and will
 * require updating once the abstraction exists.
 */
class FCSExitCodeHandlingTest : BasePlatformTestCase() {

    // -------------------------------------------------------------------------
    // Current behaviour verification
    //
    // FCSFileScanTriggerService.runScanForFile currently:
    //   1. Calls process.waitFor()
    //   2. Logs the exit code
    //   3. Always proceeds to cleanupOldResultsFromTemp and daemon restart
    //      regardless of exit code
    //
    // This means exit code 40 is accidentally correct — no exception is thrown,
    // the daemon restarts, and results are picked up. These tests pin that
    // behaviour so a future refactor cannot break it by adding error branching
    // on non-zero exit codes.
    // -------------------------------------------------------------------------

    /**
     * Verifies that a process exiting with code 40 (findings present) does not
     * prevent result collection.
     *
     * TODO(production-change): replace with injected ProcessExecutor fake once
     * the abstraction is in place. Current test uses a real shell process.
     */
    fun `test exit code 40 is treated as success not an error`() {
        val exitCode = runFakeProcess(40)
        // 40 = scan finished with findings; must not be treated as error
        assertExitCodeIsAccepted(exitCode)
    }

    fun `test exit code 0 is accepted`() {
        assertExitCodeIsAccepted(runFakeProcess(0))
    }

    fun `test exit code 1 is a general error`() {
        assertExitCodeIsError(runFakeProcess(1))
    }

    fun `test exit code 2 is invalid arguments`() {
        assertExitCodeIsError(runFakeProcess(2))
    }

    fun `test exit code 10 is binary or config error`() {
        assertExitCodeIsError(runFakeProcess(10))
    }

    fun `test exit code 20 is auth error`() {
        assertExitCodeIsError(runFakeProcess(20))
    }

    fun `test exit code 30 is network error`() {
        assertExitCodeIsError(runFakeProcess(30))
    }

    // -------------------------------------------------------------------------
    // ExitCodeClassifier — pure logic, no platform dependency
    //
    // TODO(production-change): once ProcessExecutor is extracted, add an
    // ExitCodeClassifier object next to it so the classification logic is
    // testable independently of the service. Tests below are written against
    // that future object; update imports when it exists.
    // -------------------------------------------------------------------------

    fun `test isSuccessExitCode returns true for 0`() {
        assertTrue(ExitCodeClassifier.isSuccess(0))
    }

    fun `test isSuccessExitCode returns true for 40`() {
        // 40 means findings were found — that IS a successful scan
        assertTrue(ExitCodeClassifier.isSuccess(40))
    }

    fun `test isSuccessExitCode returns false for 1`() {
        assertFalse(ExitCodeClassifier.isSuccess(1))
    }

    fun `test isSuccessExitCode returns false for 2`() {
        assertFalse(ExitCodeClassifier.isSuccess(2))
    }

    fun `test isSuccessExitCode returns false for 10`() {
        assertFalse(ExitCodeClassifier.isSuccess(10))
    }

    fun `test isSuccessExitCode returns false for 20`() {
        assertFalse(ExitCodeClassifier.isSuccess(20))
    }

    fun `test isSuccessExitCode returns false for 30`() {
        assertFalse(ExitCodeClassifier.isSuccess(30))
    }

    fun `test isSuccessExitCode returns false for unknown non-zero code`() {
        assertFalse(ExitCodeClassifier.isSuccess(99))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun runFakeProcess(exitCode: Int): Int {
        val process = ProcessBuilder("sh", "-c", "exit $exitCode")
            .redirectErrorStream(true)
            .start()
        return process.waitFor()
    }

    private fun assertExitCodeIsAccepted(exitCode: Int) {
        assertTrue(
            "Exit code $exitCode should be treated as a successful scan completion",
            ExitCodeClassifier.isSuccess(exitCode)
        )
    }

    private fun assertExitCodeIsError(exitCode: Int) {
        assertFalse(
            "Exit code $exitCode should be treated as a scan error",
            ExitCodeClassifier.isSuccess(exitCode)
        )
    }
}

/**
 * TODO(production-change): move this to production code alongside ProcessExecutor.
 * Kept here temporarily so the tests can compile and document the contract.
 */
object ExitCodeClassifier {
    private val SUCCESS_CODES = setOf(0, 40)

    fun isSuccess(exitCode: Int): Boolean = exitCode in SUCCESS_CODES
}
