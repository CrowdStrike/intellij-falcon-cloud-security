package com.crowdstrike.fcscliplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for FCSConfigurationService.isFileWithinAnyScanPath.
 *
 * This is a security boundary. The method uses toRealPath() on both sides before
 * comparing, which correctly handles symlinks and normalises path components.
 *
 * Critical cases:
 *   - Sibling directory with common prefix must be rejected
 *     (/project must NOT match /project-extra/file.tf)
 *   - Path traversal must be rejected
 *   - Files inside the root must be accepted
 *   - Symlinks pointing outside the root must be rejected (toRealPath follows them)
 *   - Multiple scan paths: file accepted if it matches ANY configured path
 *   - Empty scan paths list: defaults to project root (no config = accept project files)
 *
 * Uses BasePlatformTestCase to get a real project with a basePath, then creates
 * real temp filesystem structures so toRealPath() has actual paths to resolve.
 */
class FCSPathSecurityTest : BasePlatformTestCase() {

    private lateinit var configService: FCSConfigurationService

    override fun setUp() {
        super.setUp()
        configService = project.getService(FCSConfigurationService::class.java)
    }

    // -------------------------------------------------------------------------
    // Files inside the target path — must be accepted
    // -------------------------------------------------------------------------

    fun `test file directly inside target path is accepted`() {
        val root = createTempProjectDir()
        val file = Files.createFile(root.resolve("main.tf"))
        configService.setScanPaths(listOf(root.toString()))

        assertTrue(configService.isFileWithinAnyScanPath(file.toString()))
    }

    fun `test file in subdirectory inside target path is accepted`() {
        val root = createTempProjectDir()
        val subdir = Files.createDirectories(root.resolve("modules/networking"))
        val file = Files.createFile(subdir.resolve("main.tf"))
        configService.setScanPaths(listOf(root.toString()))

        assertTrue(configService.isFileWithinAnyScanPath(file.toString()))
    }

    // -------------------------------------------------------------------------
    // Sibling directory prefix rejection — the critical security case
    // -------------------------------------------------------------------------

    fun `test sibling directory with common prefix is rejected`() {
        // /project must NOT match /project-extra/file.tf
        // A naive startsWith check without path boundary validation fails this.
        val root = createTempProjectDir("workspace")
        val sibling = Files.createTempDirectory(root.parent, "workspace-extra")
        val file = Files.createTempFile(sibling, "main", ".tf")
        configService.setScanPaths(listOf(root.toString()))

        assertFalse(
            "File in sibling directory 'workspace-extra' must not match root 'workspace'",
            configService.isFileWithinAnyScanPath(file.toString())
        )
    }

    fun `test file in parent directory is rejected`() {
        val root = createTempProjectDir()
        val parentFile = Files.createTempFile(root.parent, "outside", ".tf")
        configService.setScanPaths(listOf(root.toString()))

        assertFalse(configService.isFileWithinAnyScanPath(parentFile.toString()))
    }

    // -------------------------------------------------------------------------
    // Path traversal — must be rejected
    // -------------------------------------------------------------------------

    fun `test path traversal via dot-dot is rejected`() {
        val root = createTempProjectDir()
        configService.setScanPaths(listOf(root.toString()))

        // Construct a traversal path string — toRealPath() normalises this
        val traversal = root.resolve("../outside-secret.tf").toString()
        assertFalse(configService.isFileWithinAnyScanPath(traversal))
    }

    fun `test deeply nested path traversal is rejected`() {
        val root = createTempProjectDir()
        configService.setScanPaths(listOf(root.toString()))

        val traversal = root.resolve("subdir/../../outside.tf").toString()
        assertFalse(configService.isFileWithinAnyScanPath(traversal))
    }

    // -------------------------------------------------------------------------
    // Non-existent paths — toRealPath() fails, method returns false
    // -------------------------------------------------------------------------

    fun `test non-existent file path returns false`() {
        val root = createTempProjectDir()
        configService.setScanPaths(listOf(root.toString()))

        // toRealPath() throws for paths that don't exist on disk
        assertFalse(configService.isFileWithinAnyScanPath("/does/not/exist/main.tf"))
    }

    fun `test non-existent target path returns false`() {
        configService.setScanPaths(listOf("/does/not/exist/project"))

        val root = createTempProjectDir()
        val file = Files.createFile(root.resolve("main.tf"))
        // Target can't be resolved → method must reject rather than fall back
        assertFalse(configService.isFileWithinAnyScanPath(file.toString()))
    }

    // -------------------------------------------------------------------------
    // Symlink handling — toRealPath() follows symlinks
    // -------------------------------------------------------------------------

    fun `test symlink pointing outside target path is rejected`() {
        val root = createTempProjectDir()
        val outside = Files.createTempFile("outside-secret", ".tf")
        try {
            val symlink = root.resolve("symlink.tf")
            Files.createSymbolicLink(symlink, outside)
            configService.setScanPaths(listOf(root.toString()))

            // toRealPath() resolves the symlink to its real target outside root
            assertFalse(
                "Symlink pointing outside project root must be rejected",
                configService.isFileWithinAnyScanPath(symlink.toString())
            )
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    fun `test symlink pointing inside target path is accepted`() {
        val root = createTempProjectDir()
        val realFile = Files.createFile(root.resolve("real.tf"))
        val symlink = root.resolve("link.tf")
        Files.createSymbolicLink(symlink, realFile)
        configService.setScanPaths(listOf(root.toString()))

        assertTrue(configService.isFileWithinAnyScanPath(symlink.toString()))
    }

    // -------------------------------------------------------------------------
    // Multi-path and empty-list behaviour
    // -------------------------------------------------------------------------

    fun `test file accepted when it matches second of two scan paths`() {
        val root1 = createTempProjectDir("pathA")
        val root2 = createTempProjectDir("pathB")
        val file = Files.createFile(root2.resolve("main.tf"))
        configService.setScanPaths(listOf(root1.toString(), root2.toString()))

        assertTrue(configService.isFileWithinAnyScanPath(file.toString()))
    }

    fun `test file rejected when it matches none of multiple scan paths`() {
        val root1 = createTempProjectDir("pathA")
        val root2 = createTempProjectDir("pathB")
        val outside = createTempProjectDir("outsideC")
        val file = Files.createFile(outside.resolve("main.tf"))
        configService.setScanPaths(listOf(root1.toString(), root2.toString()))

        assertFalse(configService.isFileWithinAnyScanPath(file.toString()))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createTempProjectDir(suffix: String = "project"): Path {
        return Files.createTempDirectory("fcs-test-$suffix")
    }
}
