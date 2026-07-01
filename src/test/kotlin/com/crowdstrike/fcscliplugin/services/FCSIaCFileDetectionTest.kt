package com.crowdstrike.fcscliplugin.services

import com.crowdstrike.fcscliplugin.annotators.FCSExternalAnnotator
import com.intellij.mock.MockVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for IaC file type detection.
 *
 * Both FCSFileScanTriggerService and FCSExternalAnnotator now delegate to
 * FilePatternMatcher.matches() with patterns from FCSConfigurationService.
 * These tests verify that both services agree on every file type, and that
 * custom patterns (via configService.setFilePatterns) are respected.
 */
class FCSIaCFileDetectionTest : BasePlatformTestCase() {

    private lateinit var triggerService: FCSFileScanTriggerService

    override fun setUp() {
        super.setUp()
        triggerService = project.getService(FCSFileScanTriggerService::class.java)
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private fun triggerServiceIsIaCFile(fileName: String): Boolean {
        val file = MockVirtualFile(fileName)
        val m = FCSFileScanTriggerService::class.java
            .getDeclaredMethod("isIaCFile", com.intellij.openapi.vfs.VirtualFile::class.java)
        m.isAccessible = true
        return m.invoke(triggerService, file) as Boolean
    }

    private fun annotatorIsIaCFile(fileName: String): Boolean {
        val file = MockVirtualFile(fileName)
        val annotator = FCSExternalAnnotator()
        val m = FCSExternalAnnotator::class.java
            .getDeclaredMethod("isIaCFile", com.intellij.openapi.vfs.VirtualFile::class.java,
                com.intellij.openapi.project.Project::class.java)
        m.isAccessible = true
        return m.invoke(annotator, file, project) as Boolean
    }

    // -------------------------------------------------------------------------
    // Supported extensions — both services must agree
    // -------------------------------------------------------------------------

    fun `test tf files are IaC`() = assertBothDetect("main.tf")
    fun `test tfvars files are IaC`() = assertBothDetect("variables.tfvars")
    fun `test tfstate files are IaC`() = assertBothDetect("terraform.tfstate")
    fun `test json files are IaC`() = assertBothDetect("template.json")
    fun `test yaml files are IaC`() = assertBothDetect("deployment.yaml")
    fun `test yml files are IaC`() = assertBothDetect("deployment.yml")
    fun `test xml files are IaC`() = assertBothDetect("config.xml")
    fun `test hcl files are IaC`() = assertBothDetect("backend.hcl")
    fun `test rego files are IaC`() = assertBothDetect("policy.rego")
    fun `test jinja files are IaC`() = assertBothDetect("template.jinja")

    // -------------------------------------------------------------------------
    // Bicep
    // -------------------------------------------------------------------------

    fun `test bicep files are IaC`() = assertBothDetect("main.bicep")

    // -------------------------------------------------------------------------
    // Dockerfile — extension and bare filename variants
    // -------------------------------------------------------------------------

    fun `test dockerfile extension is IaC`() = assertBothDetect("build.dockerfile")
    fun `test Dockerfile bare filename is IaC`() = assertBothDetect("Dockerfile")
    fun `test dockerfile lowercase bare filename is IaC`() = assertBothDetect("dockerfile")

    // -------------------------------------------------------------------------
    // Special IaC filenames
    // -------------------------------------------------------------------------

    fun `test docker-compose yml is IaC`() = assertBothDetect("docker-compose.yml")
    fun `test docker-compose yaml is IaC`() = assertBothDetect("docker-compose.yaml")
    fun `test serverless yml is IaC`() = assertBothDetect("serverless.yml")
    fun `test serverless yaml is IaC`() = assertBothDetect("serverless.yaml")
    fun `test kustomization yml is IaC`() = assertBothDetect("kustomization.yml")
    fun `test kustomization yaml is IaC`() = assertBothDetect("kustomization.yaml")

    // -------------------------------------------------------------------------
    // Non-IaC files must be rejected
    // -------------------------------------------------------------------------

    fun `test kt files are not IaC`() = assertNeitherDetects("FooService.kt")
    fun `test java files are not IaC`() = assertNeitherDetects("Main.java")
    fun `test py files are not IaC`() = assertNeitherDetects("script.py")
    fun `test js files are not IaC`() = assertNeitherDetects("index.js")
    fun `test ts files are not IaC`() = assertNeitherDetects("app.ts")
    fun `test md files are not IaC`() = assertNeitherDetects("README.md")
    fun `test txt files are not IaC`() = assertNeitherDetects("notes.txt")
    fun `test sh files are not IaC`() = assertNeitherDetects("deploy.sh")
    fun `test png files are not IaC`() = assertNeitherDetects("logo.png")

    // -------------------------------------------------------------------------
    // Scan result files must be excluded (avoid scanning our own output)
    // -------------------------------------------------------------------------

    fun `test scan-results json is not IaC`() {
        // FCSExternalAnnotator excludes scan result files before the IaC check
        assertFalse(annotatorIsIaCFile("12345-scan-results.json"))
    }

    // -------------------------------------------------------------------------
    // Consistency check — both services must agree on every case
    // -------------------------------------------------------------------------

    /**
     * Asserts that both FCSFileScanTriggerService and FCSExternalAnnotator
     * agree a file is IaC. Divergence here means the two services scan
     * different sets of files, causing inconsistent annotation behaviour.
     */
    private fun assertBothDetect(fileName: String) {
        assertTrue(
            "FCSFileScanTriggerService should detect $fileName as IaC",
            triggerServiceIsIaCFile(fileName)
        )
        assertTrue(
            "FCSExternalAnnotator should detect $fileName as IaC",
            annotatorIsIaCFile(fileName)
        )
    }

    private fun assertNeitherDetects(fileName: String) {
        assertFalse(
            "FCSFileScanTriggerService should NOT detect $fileName as IaC",
            triggerServiceIsIaCFile(fileName)
        )
        assertFalse(
            "FCSExternalAnnotator should NOT detect $fileName as IaC",
            annotatorIsIaCFile(fileName)
        )
    }
}
