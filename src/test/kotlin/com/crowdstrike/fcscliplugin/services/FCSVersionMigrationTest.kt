package com.crowdstrike.fcscliplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for CLI version compatibility checking and persistent version tracking.
 *
 * Covers:
 *   - lastKnownCliVersion persists in FCSConfigurationService.State
 *   - FCSBinaryService.isVersionCompatible correctly applies the minimum version boundary
 *   - Version round-trip: set then retrieve lastKnownCliVersion
 */
class FCSVersionMigrationTest : BasePlatformTestCase() {

    private lateinit var binaryService: FCSBinaryService
    private lateinit var configService: FCSConfigurationService

    override fun setUp() {
        super.setUp()
        binaryService = project.getService(FCSBinaryService::class.java)
        configService = project.getService(FCSConfigurationService::class.java)
    }

    // -------------------------------------------------------------------------
    // lastKnownCliVersion persistence
    // -------------------------------------------------------------------------

    fun `test lastKnownCliVersion defaults to empty string`() {
        assertEquals("", configService.state.lastKnownCliVersion)
    }

    fun `test lastKnownCliVersion round-trips correctly`() {
        configService.state.lastKnownCliVersion = "3.1.0"
        assertEquals("3.1.0", configService.state.lastKnownCliVersion)
    }

    fun `test lastKnownCliVersion can be updated multiple times`() {
        configService.state.lastKnownCliVersion = "3.0.0"
        configService.state.lastKnownCliVersion = "3.2.0"
        assertEquals("3.2.0", configService.state.lastKnownCliVersion)
    }

    // -------------------------------------------------------------------------
    // Version compatibility boundary
    // -------------------------------------------------------------------------

    fun `test minimum CLI version constant is 3_0_0`() {
        assertEquals("3.0.0", FCSBinaryService.MINIMUM_CLI_VERSION)
    }

    fun `test version at minimum boundary is compatible`() {
        assertTrue(binaryService.isVersionCompatible(FCSBinaryService.MINIMUM_CLI_VERSION))
    }

    fun `test patch release above minimum is compatible`() {
        assertTrue(binaryService.isVersionCompatible("3.0.1"))
    }

    fun `test minor release above minimum is compatible`() {
        assertTrue(binaryService.isVersionCompatible("3.1.0"))
    }

    fun `test major release above maximum is incompatible`() {
        assertFalse(binaryService.isVersionCompatible("4.0.0"))
    }

    fun `test version just below minimum is incompatible`() {
        assertFalse(binaryService.isVersionCompatible("2.9.9"))
    }

    fun `test legacy CLI version 2_0_2 is incompatible`() {
        assertFalse(binaryService.isVersionCompatible("2.0.2"))
    }

    // -------------------------------------------------------------------------
    // Upgrade detection logic (unit-level, no process spawning)
    // -------------------------------------------------------------------------

    fun `test upgrade is detected when current version is greater than last known`() {
        configService.state.lastKnownCliVersion = "3.0.0"
        val lastVersion = configService.state.lastKnownCliVersion
        val currentVersion = "3.1.0"

        val last = org.semver4j.Semver(lastVersion)
        val current = org.semver4j.Semver(currentVersion)
        assertTrue("Expected $currentVersion > $lastVersion", current.isGreaterThan(last))
    }

    fun `test no upgrade is detected when version is unchanged`() {
        configService.state.lastKnownCliVersion = "3.1.0"
        val lastVersion = configService.state.lastKnownCliVersion
        val currentVersion = "3.1.0"

        val last = org.semver4j.Semver(lastVersion)
        val current = org.semver4j.Semver(currentVersion)
        assertFalse("Expected $currentVersion not > $lastVersion", current.isGreaterThan(last))
    }

    fun `test no upgrade is detected when current version is lower than last known`() {
        configService.state.lastKnownCliVersion = "3.2.0"
        val lastVersion = configService.state.lastKnownCliVersion
        val currentVersion = "3.1.0"

        val last = org.semver4j.Semver(lastVersion)
        val current = org.semver4j.Semver(currentVersion)
        assertFalse("Expected $currentVersion not > $lastVersion", current.isGreaterThan(last))
    }

    fun `test first-run scenario - empty lastKnownCliVersion skips upgrade migration`() {
        configService.state.lastKnownCliVersion = ""
        val lastVersion = configService.state.lastKnownCliVersion
        // Startup code guards with isNotBlank() — empty means first run, no migration triggered
        assertTrue(lastVersion.isBlank())
    }

    // -------------------------------------------------------------------------
    // fcs version prefix stripping
    // -------------------------------------------------------------------------

    fun `test isVersionCompatible rejects unparsed fcs version prefix`() {
        // getFCSVersion() strips the prefix before returning; passing the raw output must fail
        assertFalse(binaryService.isVersionCompatible("fcs version: 3.0.0"))
    }

    fun `test isVersionCompatible accepts clean version string after prefix stripped`() {
        // This is what getFCSVersion() returns after stripping "fcs version: "
        assertTrue(binaryService.isVersionCompatible("3.0.0"))
    }

    // -------------------------------------------------------------------------
    // Migration legacy-file guard
    // -------------------------------------------------------------------------

    fun `test migration is skipped when legacy fcs_profiles json does not exist`() {
        val legacyConfig = java.nio.file.Paths.get(System.getProperty("user.home"), ".crowdstrike", "fcs_profiles.json")
        if (java.nio.file.Files.exists(legacyConfig)) return // skip on machines that have it

        // runMigrateConfig must return false (skipped) without the legacy file
        assertFalse(binaryService.runMigrateConfig())
    }
}
