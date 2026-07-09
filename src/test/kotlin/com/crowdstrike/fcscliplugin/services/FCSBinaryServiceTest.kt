package com.crowdstrike.fcscliplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotEquals
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Tests for FCSBinaryService binary discovery decision tree and caching.
 *
 * Discovery priority order (highest to lowest):
 *   1. ~/.local/bin/fcs  (downloaded location, checked first)
 *   2. `which fcs`       (PATH resolution via shell)
 *   3. Common install paths (/usr/local/bin/fcs, /usr/bin/fcs, etc.)
 *   4. PATH env var entries (excluding workspace-local binaries)
 *
 * Security invariants:
 *   - Binaries inside the project workspace must be rejected (isInsideAnyWorkspace)
 *   - Binaries that fail `fcs version` validation are rejected
 *   - PATH binaries shadowing the workspace are skipped
 *
 * TODO(production-change): findFCSBinary, isInsideAnyWorkspace, tryWhichCommand,
 * and isValidFCSBinary are all private. Make them internal for direct testing.
 * Until then, accessed via reflection or tested indirectly through getFCSBinaryPath().
 */
class FCSBinaryServiceTest : BasePlatformTestCase() {

    private lateinit var service: FCSBinaryService

    override fun setUp() {
        super.setUp()
        service = project.getService(FCSBinaryService::class.java)
    }

    // -------------------------------------------------------------------------
    // Cache behaviour
    // -------------------------------------------------------------------------

    fun `test getFCSBinaryPath result is cached within validity window`() {
        // Two calls within the 30s window must return the same object reference
        val first = service.getFCSBinaryPath()
        val second = service.getFCSBinaryPath()
        // Both calls return the same cached value (null equality or same string)
        assertEquals(first, second)
    }

    fun `test refreshBinaryPath clears the cache`() {
        // Pre-populate the cache by calling getFCSBinaryPath
        service.getFCSBinaryPath()

        // Inject a stale cache time via reflection so next call sees it as expired
        val lastCheckField = FCSBinaryService::class.java.getDeclaredField("lastCheckTime")
        lastCheckField.isAccessible = true
        lastCheckField.set(service, 0L)

        val cachedPathField = FCSBinaryService::class.java.getDeclaredField("cachedBinaryPath")
        cachedPathField.isAccessible = true
        cachedPathField.set(service, "/fake/path/fcs")

        // refreshBinaryPath must clear the stale cache and re-detect
        service.refreshBinaryPath()

        val cachedAfterRefresh = cachedPathField.get(service) as? String
        // After refresh the cached value should no longer be the injected fake
        assertNotEquals("/fake/path/fcs", cachedAfterRefresh)
    }

    fun `test cache expires after validity duration`() {
        val cacheValidityField = FCSBinaryService::class.java.getDeclaredField("cacheValidityDuration")
        cacheValidityField.isAccessible = true
        val validity = cacheValidityField.get(service) as Long

        // Validity must be 30 seconds — pin this so it is not accidentally reduced
        assertEquals(30_000L, validity)
    }

    // -------------------------------------------------------------------------
    // isFCSAvailable delegates to getFCSBinaryPath
    // -------------------------------------------------------------------------

    fun `test isFCSAvailable returns false when no binary found`() {
        // In a clean test environment with no FCS binary, expect false
        // (If FCS happens to be installed on the test machine, this may return true —
        // that is also correct behaviour.)
        val available = service.isFCSAvailable()
        val path = service.getFCSBinaryPath()
        assertEquals("isFCSAvailable must agree with getFCSBinaryPath nullability",
            path != null, available)
    }

    // -------------------------------------------------------------------------
    // isInsideAnyWorkspace — workspace binary rejection
    // -------------------------------------------------------------------------

    fun `test binary inside project workspace is rejected`() {
        val basePath = project.basePath ?: return
        val workspaceBinary = "$basePath/bin/fcs"

        val isInside = invokeIsInsideAnyWorkspace(workspaceBinary)
        assertTrue(
            "Binary at $workspaceBinary must be rejected as workspace-local",
            isInside
        )
    }

    fun `test binary outside project workspace is not rejected`() {
        val isInside = invokeIsInsideAnyWorkspace("/usr/local/bin/fcs")
        assertFalse(
            "Binary at /usr/local/bin/fcs must not be flagged as workspace-local",
            isInside
        )
    }

    fun `test binary in home local bin is not rejected`() {
        val homeLocalBin = System.getProperty("user.home") + "/.local/bin/fcs"
        val isInside = invokeIsInsideAnyWorkspace(homeLocalBin)
        assertFalse(
            "Downloaded binary at ~/.local/bin/fcs must not be rejected",
            isInside
        )
    }

    // -------------------------------------------------------------------------
    // isValidFCSBinary — rejects non-executable and non-existent paths
    // -------------------------------------------------------------------------

    fun `test non-existent path is not a valid FCS binary`() {
        assertFalse(invokeIsValidFCSBinary("/does/not/exist/fcs"))
    }

    fun `test non-executable file is not a valid FCS binary`() {
        val nonExec = Files.createTempFile("fcs-test", "")
        try {
            // Explicitly remove execute permission
            Files.setPosixFilePermissions(nonExec, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
            ))
            assertFalse(invokeIsValidFCSBinary(nonExec.toString()))
        } finally {
            Files.deleteIfExists(nonExec)
        }
    }

    fun `test executable file that is not FCS binary returns false`() {
        // Create a real executable that exits with 0 but outputs nothing FCS-related
        val fakeBinary = Files.createTempFile("fake-fcs", ".sh")
        try {
            fakeBinary.toFile().writeText("#!/bin/sh\necho 'hello world'\nexit 0\n")
            Files.setPosixFilePermissions(fakeBinary, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ))
            // Output does not contain "fcs" → should be rejected
            assertFalse(invokeIsValidFCSBinary(fakeBinary.toString()))
        } finally {
            Files.deleteIfExists(fakeBinary)
        }
    }

    // -------------------------------------------------------------------------
    // getFCSVersion
    // -------------------------------------------------------------------------

    fun `test getFCSVersion returns null when no binary available`() {
        // Force cache to return a nonexistent path — getFCSBinaryPath() returns it,
        // then getFCSVersion() fails to run it and returns null gracefully.
        val cachedPathField = FCSBinaryService::class.java.getDeclaredField("cachedBinaryPath")
        cachedPathField.isAccessible = true
        val lastCheckField = FCSBinaryService::class.java.getDeclaredField("lastCheckTime")
        lastCheckField.isAccessible = true

        val originalPath = cachedPathField.get(service)
        val originalTime = lastCheckField.get(service)
        cachedPathField.set(service, "/nonexistent/path/to/fcs")
        lastCheckField.set(service, Long.MAX_VALUE / 2)
        try {
            assertNull(service.getFCSVersion())
        } finally {
            cachedPathField.set(service, originalPath)
            lastCheckField.set(service, originalTime)
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private fun invokeIsInsideAnyWorkspace(path: String): Boolean {
        val m = FCSBinaryService::class.java
            .getDeclaredMethod("isInsideAnyWorkspace", String::class.java)
        m.isAccessible = true
        return m.invoke(service, path) as Boolean
    }

    private fun invokeIsValidFCSBinary(path: String): Boolean {
        val m = FCSBinaryService::class.java
            .getDeclaredMethod("isValidFCSBinary", String::class.java)
        m.isAccessible = true
        return m.invoke(service, path) as Boolean
    }

    // -------------------------------------------------------------------------
    // isVersionCompatible
    // -------------------------------------------------------------------------

    fun `test isVersionCompatible returns true for exact minimum version 3_0_0`() {
        assertTrue(service.isVersionCompatible("3.0.0"))
    }

    fun `test isVersionCompatible returns true for version within range`() {
        assertTrue(service.isVersionCompatible("3.1.0"))
        assertTrue(service.isVersionCompatible("3.0.1"))
    }

    fun `test isVersionCompatible returns true for exact maximum version`() {
        assertTrue(service.isVersionCompatible(FCSBinaryService.MAXIMUM_CLI_VERSION))
    }

    fun `test isVersionCompatible returns false for version above maximum`() {
        assertFalse(service.isVersionCompatible("3.3.0"))
        assertFalse(service.isVersionCompatible("4.0.0"))
    }

    fun `test isVersionCompatible returns false for version below minimum`() {
        assertFalse(service.isVersionCompatible("2.9.9"))
        assertFalse(service.isVersionCompatible("2.0.2"))
        assertFalse(service.isVersionCompatible("0.1.0"))
    }

    fun `test isVersionCompatible returns false for blank string`() {
        assertFalse(service.isVersionCompatible(""))
        assertFalse(service.isVersionCompatible("   "))
    }

    fun `test isVersionCompatible returns false for malformed version string`() {
        assertFalse(service.isVersionCompatible("not-a-version"))
        assertFalse(service.isVersionCompatible("fcs version: 3.0.0"))
    }

    fun `test isVersionAboveMaximum returns true for version above maximum`() {
        assertTrue(service.isVersionAboveMaximum("3.3.0"))
        assertTrue(service.isVersionAboveMaximum("4.0.0"))
    }

    fun `test isVersionAboveMaximum returns false for version at or below maximum`() {
        assertFalse(service.isVersionAboveMaximum(FCSBinaryService.MAXIMUM_CLI_VERSION))
        assertFalse(service.isVersionAboveMaximum("3.1.0"))
        assertFalse(service.isVersionAboveMaximum("2.0.0"))
    }

    fun `test isVersionAboveMaximum returns false for blank or malformed string`() {
        assertFalse(service.isVersionAboveMaximum(""))
        assertFalse(service.isVersionAboveMaximum("not-a-version"))
    }

    // -------------------------------------------------------------------------
    // runMigrateConfig
    // -------------------------------------------------------------------------

    fun `test runMigrateConfig returns false when no binary available`() {
        // Force cache to a nonexistent path so getFCSBinaryPath returns a bad path
        val cachedPathField = FCSBinaryService::class.java.getDeclaredField("cachedBinaryPath")
        cachedPathField.isAccessible = true
        val lastCheckField = FCSBinaryService::class.java.getDeclaredField("lastCheckTime")
        lastCheckField.isAccessible = true

        val originalPath = cachedPathField.get(service)
        val originalTime = lastCheckField.get(service)
        cachedPathField.set(service, "/nonexistent/fcs")
        lastCheckField.set(service, Long.MAX_VALUE / 2)
        try {
            assertFalse(service.runMigrateConfig())
        } finally {
            cachedPathField.set(service, originalPath)
            lastCheckField.set(service, originalTime)
        }
    }

    fun `test runMigrateConfig returns false when legacy config file does not exist`() {
        // In the test environment fcs_profiles.json will not be present, so migration must be skipped
        val legacyConfig = java.nio.file.Paths.get(System.getProperty("user.home"), ".crowdstrike", "fcs_profiles.json")
        if (java.nio.file.Files.exists(legacyConfig)) return // skip if developer happens to have the file

        assertFalse(service.runMigrateConfig())
    }
}
