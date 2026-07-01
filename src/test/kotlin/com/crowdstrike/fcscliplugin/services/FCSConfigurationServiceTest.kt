package com.crowdstrike.fcscliplugin.services

import com.crowdstrike.fcscliplugin.services.FCSConfigurationService.SeverityLevel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FCSConfigurationServiceTest : BasePlatformTestCase() {

    private lateinit var state: FCSConfigurationService.State

    override fun setUp() {
        super.setUp()
        state = FCSConfigurationService.State()
    }

    // --- State defaults ---

    fun `test default state has empty scanPaths`() {
        assertTrue(state.scanPaths.isEmpty())
    }

    fun `test default state has empty filePatterns`() {
        assertTrue(state.filePatterns.isEmpty())
    }

    fun `test default state has outputPath of tmp`() {
        assertEquals("tmp", state.outputPath)
    }

    fun `test default state has empty selectedSeverities`() {
        assertTrue(state.selectedSeverities.isEmpty())
    }

    fun `test default state has autoScanOnSave true`() {
        assertTrue(state.autoScanOnSave)
    }

    fun `test default state has pluginEnabled true`() {
        assertTrue(state.pluginEnabled)
    }

    fun `test default state has excludeSecrets false`() {
        assertFalse(state.excludeSecrets)
    }

    fun `test default state has uploadResults false`() {
        assertFalse(state.uploadResults)
    }

    fun `test default state has scanTimeout of 300`() {
        assertEquals(300, state.scanTimeout)
    }

    // --- SeverityLevel enum ---

    fun `test SeverityLevel has five values in ascending severity order`() {
        val values = SeverityLevel.values()
        assertEquals(5, values.size)
        assertEquals(
            listOf(SeverityLevel.INFORMATIONAL, SeverityLevel.LOW, SeverityLevel.MEDIUM, SeverityLevel.HIGH, SeverityLevel.CRITICAL),
            values.toList()
        )
    }

    // --- buildScanCommand ---

    fun `test buildScanCommand includes binary path and core flags`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        val cmd = svc.buildScanCommand("/usr/bin/fcs")
        assertEquals("/usr/bin/fcs", cmd[0])
        assertEquals("scan", cmd[1])
        assertEquals("iac", cmd[2])
        assertTrue(cmd.contains("--path"))
        assertTrue(cmd.contains("--output-path"))
        assertTrue(cmd.contains("--policy-rule"))
        assertTrue(cmd.contains("local"))
        assertTrue(cmd.contains("--fail-on"))
    }

    fun `test buildScanCommand omits severities when all selected`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertFalse(svc.buildScanCommand("/fcs").contains("--severities"))
    }

    fun `test buildScanCommand includes severities when subset selected`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.HIGH, SeverityLevel.CRITICAL))
        val cmd = svc.buildScanCommand("/fcs")
        val idx = cmd.indexOf("--severities")
        assertTrue("--severities flag should be present", idx >= 0)
        val sevArg = cmd[idx + 1]
        assertTrue(sevArg.contains("high") && sevArg.contains("critical"))
    }

    fun `test buildScanCommand includes disable-secrets-scan when excludeSecrets is true`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setExcludeSecrets(true)
        assertTrue(svc.buildScanCommand("/fcs").contains("--disable-secrets-scan"))
    }

    fun `test buildScanCommand omits disable-secrets-scan by default`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertFalse(svc.buildScanCommand("/fcs").contains("--disable-secrets-scan"))
    }

    fun `test buildScanCommand includes upload when uploadResults is true`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setUploadResults(true)
        assertTrue(svc.buildScanCommand("/fcs").contains("--upload"))
    }

    fun `test buildScanCommand uses comma-separated paths for multiple scan paths`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setScanPaths(listOf("/proj/a", "/proj/b"))
        val cmd = svc.buildScanCommand("/fcs")
        val idx = cmd.indexOf("--path")
        assertTrue(idx >= 0)
        assertEquals("/proj/a,/proj/b", cmd[idx + 1])
    }

    // --- buildIndividualFileScanCommand ---

    fun `test buildIndividualFileScanCommand omits upload by default`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertFalse(svc.buildIndividualFileScanCommand("/fcs", "/proj/main.tf").contains("--upload"))
    }

    fun `test buildIndividualFileScanCommand includes upload when uploadResults is true`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setUploadResults(true)
        assertTrue(svc.buildIndividualFileScanCommand("/fcs", "/proj/main.tf").contains("--upload"))
    }

    fun `test buildIndividualFileScanCommand uses given filePath for --path`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        val cmd = svc.buildIndividualFileScanCommand("/fcs", "/proj/src/main.tf")
        val pathIdx = cmd.indexOf("--path")
        assertTrue(pathIdx >= 0)
        assertEquals("/proj/src/main.tf", cmd[pathIdx + 1])
    }

    fun `test buildIndividualFileScanCommand includes all core flags`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        val cmd = svc.buildIndividualFileScanCommand("/fcs", "/proj/a.tf")
        assertTrue(cmd.contains("--output-path"))
        assertTrue(cmd.contains("--policy-rule"))
        assertTrue(cmd.contains("local"))
        assertTrue(cmd.contains("--fail-on"))
    }

    // --- getEffectiveSeverities ---

    fun `test getEffectiveSeverities returns all levels when no threshold set`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertEquals(SeverityLevel.values().toSet(), svc.getEffectiveSeverities())
    }

    fun `test selecting HIGH threshold yields HIGH and CRITICAL`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.HIGH))
        val effective = svc.getEffectiveSeverities()
        assertTrue(effective.contains(SeverityLevel.HIGH))
        assertTrue(effective.contains(SeverityLevel.CRITICAL))
        assertFalse(effective.contains(SeverityLevel.MEDIUM))
        assertFalse(effective.contains(SeverityLevel.LOW))
        assertFalse(effective.contains(SeverityLevel.INFORMATIONAL))
    }

    fun `test selecting MEDIUM threshold yields MEDIUM HIGH and CRITICAL`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.MEDIUM))
        val effective = svc.getEffectiveSeverities()
        assertTrue(effective.contains(SeverityLevel.MEDIUM))
        assertTrue(effective.contains(SeverityLevel.HIGH))
        assertTrue(effective.contains(SeverityLevel.CRITICAL))
        assertFalse(effective.contains(SeverityLevel.LOW))
        assertFalse(effective.contains(SeverityLevel.INFORMATIONAL))
    }

    fun `test selecting CRITICAL threshold yields only CRITICAL`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.CRITICAL))
        assertEquals(setOf(SeverityLevel.CRITICAL), svc.getEffectiveSeverities())
    }

    fun `test selecting INFORMATIONAL threshold yields all levels`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.INFORMATIONAL))
        assertEquals(SeverityLevel.values().toSet(), svc.getEffectiveSeverities())
    }

    fun `test selecting LOW threshold yields LOW MEDIUM HIGH and CRITICAL`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.LOW))
        val effective = svc.getEffectiveSeverities()
        assertTrue(effective.contains(SeverityLevel.LOW))
        assertTrue(effective.contains(SeverityLevel.MEDIUM))
        assertTrue(effective.contains(SeverityLevel.HIGH))
        assertTrue(effective.contains(SeverityLevel.CRITICAL))
        assertFalse(effective.contains(SeverityLevel.INFORMATIONAL))
    }

    fun `test buildScanCommand uses cascade for severity threshold`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setSelectedSeverities(setOf(SeverityLevel.HIGH))
        val cmd = svc.buildScanCommand("/fcs")
        val idx = cmd.indexOf("--severities")
        assertTrue("--severities flag must be present", idx >= 0)
        val sevArg = cmd[idx + 1]
        assertTrue("high must be in severities arg", sevArg.contains("high"))
        assertTrue("critical must be in severities arg", sevArg.contains("critical"))
        assertFalse("medium must not appear for HIGH threshold", sevArg.contains("medium"))
        assertFalse("informational must not appear for HIGH threshold", sevArg.contains("informational"))
    }

    // --- getFilePatterns ---

    fun `test getFilePatterns returns DEFAULT_FILE_PATTERNS when empty`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertEquals(FCSConfigurationService.DEFAULT_FILE_PATTERNS, svc.getFilePatterns())
    }

    fun `test DEFAULT_FILE_PATTERNS includes bicep`() {
        assertTrue(FCSConfigurationService.DEFAULT_FILE_PATTERNS.contains("*.bicep"))
    }

    fun `test getFilePatterns returns custom patterns when set`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setFilePatterns(listOf("*.tf", "*.yaml"))
        assertEquals(listOf("*.tf", "*.yaml"), svc.getFilePatterns())
    }

    fun `test setFilePatterns with empty list reverts to defaults`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setFilePatterns(listOf("*.tf"))
        svc.setFilePatterns(emptyList())
        assertEquals(FCSConfigurationService.DEFAULT_FILE_PATTERNS, svc.getFilePatterns())
    }

    // --- scanTimeout ---

    fun `test getScanTimeout returns 300 by default`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertEquals(300, svc.getScanTimeout())
    }

    fun `test setScanTimeout persists custom value`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setScanTimeout(600)
        assertEquals(600, svc.getScanTimeout())
    }

    fun `test buildScanCommand includes --timeout with default value`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        val cmd = svc.buildScanCommand("/fcs")
        val idx = cmd.indexOf("--timeout")
        assertTrue("--timeout flag must be present", idx >= 0)
        assertEquals("300", cmd[idx + 1])
    }

    fun `test buildScanCommand includes --timeout with custom value`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setScanTimeout(600)
        val cmd = svc.buildScanCommand("/fcs")
        val idx = cmd.indexOf("--timeout")
        assertTrue(idx >= 0)
        assertEquals("600", cmd[idx + 1])
    }

    fun `test buildIndividualFileScanCommand includes --timeout with default value`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        val cmd = svc.buildIndividualFileScanCommand("/fcs", "/proj/main.tf")
        val idx = cmd.indexOf("--timeout")
        assertTrue("--timeout flag must be present in individual file scan", idx >= 0)
        assertEquals("300", cmd[idx + 1])
    }

    fun `test buildIndividualFileScanCommand includes --timeout with custom value`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setScanTimeout(120)
        val cmd = svc.buildIndividualFileScanCommand("/fcs", "/proj/main.tf")
        val idx = cmd.indexOf("--timeout")
        assertTrue(idx >= 0)
        assertEquals("120", cmd[idx + 1])
    }

    // --- platforms ---

    fun `test default state has empty platforms`() {
        assertTrue(state.platforms.isEmpty())
    }

    fun `test getPlatforms returns empty list by default`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertTrue(svc.getPlatforms().isEmpty())
    }

    fun `test setPlatforms persists values`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setPlatforms(listOf("Terraform", "Kubernetes"))
        assertEquals(listOf("Terraform", "Kubernetes"), svc.getPlatforms())
    }

    fun `test buildScanCommand omits --platforms when list is empty`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertFalse(svc.buildScanCommand("/fcs").contains("--platforms"))
    }

    fun `test buildScanCommand includes --platforms when configured`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setPlatforms(listOf("Terraform", "Kubernetes"))
        val cmd = svc.buildScanCommand("/fcs")
        val idx = cmd.indexOf("--platforms")
        assertTrue("--platforms flag must be present", idx >= 0)
        assertEquals("Terraform,Kubernetes", cmd[idx + 1])
    }

    fun `test buildIndividualFileScanCommand omits --platforms when list is empty`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        assertFalse(svc.buildIndividualFileScanCommand("/fcs", "/proj/main.tf").contains("--platforms"))
    }

    fun `test buildIndividualFileScanCommand includes --platforms when configured`() {
        val svc = FakeConfigurationService(projectBase = "/proj")
        svc.setPlatforms(listOf("CloudFormation"))
        val cmd = svc.buildIndividualFileScanCommand("/fcs", "/proj/main.tf")
        val idx = cmd.indexOf("--platforms")
        assertTrue("--platforms flag must be present in individual file scan", idx >= 0)
        assertEquals("CloudFormation", cmd[idx + 1])
    }
}

/**
 * Subclass that replaces the IntelliJ Project dependency with a fixed base path,
 * allowing command-building logic to be tested without a running IDE.
 */
private class FakeConfigurationService(private val projectBase: String) :
    FCSConfigurationService(com.intellij.mock.MockProject(null, com.intellij.openapi.util.Disposer.newDisposable())) {

    override fun getScanPaths(): List<String> = getState().scanPaths.ifEmpty { listOf(projectBase) }
    override fun getTargetPath(): String = getScanPaths().first()
    override fun getTemporaryOutputPath(): String = "/tmp/fcs-scan-results"
}
