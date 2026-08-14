package com.crowdstrike.fcscliplugin

import com.crowdstrike.fcscliplugin.services.FCSBinaryService
import com.crowdstrike.fcscliplugin.services.FCSConfigurationService
import com.crowdstrike.fcscliplugin.services.FCSResultsService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Integration tests that invoke a real FCS CLI binary.
 *
 * Requires the binary to be present on PATH (placed there by test-intellij.sh).
 * Tests fail if the binary is not available — this is intentional.
 * Run via: bash scripts/test-intellij.sh from the repo root.
 */
class FCSRealBinaryIntegrationTest : BasePlatformTestCase() {

    private lateinit var binaryService: FCSBinaryService
    private lateinit var configService: FCSConfigurationService
    private lateinit var resultsService: FCSResultsService

    private val fixturesDir = File("testData/sample-project")

    override fun setUp() {
        super.setUp()
        binaryService = project.getService(FCSBinaryService::class.java)
        configService = project.getService(FCSConfigurationService::class.java)
        resultsService = project.getService(FCSResultsService::class.java)
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private fun runScan(filePath: String): Triple<Int, FCSResultsService.ScanResults?, String?> {
        val binaryPath = binaryService.getFCSBinaryPath()
        if (binaryPath == null) {
            fail("FCS CLI binary not found on PATH -- run via scripts/test-intellij.sh")
            return Triple(-1, null, null)
        }
        val command = configService.buildIndividualFileScanCommand(binaryPath, filePath)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val exitCode = process.waitFor()
        if (exitCode != 0 && exitCode != 50) return Triple(exitCode, null, null)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fcs-scan-results")
        val resultFile = tempDir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.lastModified() }
        val rawJson = resultFile?.readText()
        val results = rawJson?.let { resultsService.parseResultsJson(it) }
        return Triple(exitCode, results, rawJson)
    }

    // -------------------------------------------------------------------------
    // Binary discovery
    // -------------------------------------------------------------------------

    fun `test getFCSBinaryPath returns non-null when binary is on PATH`() {
        val path = binaryService.getFCSBinaryPath()
        assertNotNull("FCS CLI binary not found on PATH -- run via scripts/test-intellij.sh", path)
        println("✅ Found FCS CLI at $path")
    }

    fun `test isVersionCompatible returns true for real binary version`() {
        val version = binaryService.getFCSVersion()
        assertNotNull("Could not get FCS CLI version", version)
        assertTrue(
            "Version $version is not compatible",
            binaryService.isVersionCompatible(version!!)
        )
        println("✅ FCS CLI version $version is compatible")
    }

    // -------------------------------------------------------------------------
    // Scan execution
    // -------------------------------------------------------------------------

    fun `test real scan on terraform fixture produces findings`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (exitCode, results, _) = runScan(file)
        assertTrue("Scan should succeed (got exit code $exitCode)", exitCode == 0 || exitCode == 50)
        assertNotNull("Results should not be null", results)
        assertTrue("Terraform fixture should produce findings", results!!.results.isNotEmpty())
        println("✅ terraform: ${results.results.size} findings")
    }

    fun `test real scan on cloudformation fixture produces findings`() {
        val file = File(fixturesDir, "cloudformation-example.json").absolutePath
        val (exitCode, results, _) = runScan(file)
        assertTrue("Scan should succeed (got exit code $exitCode)", exitCode == 0 || exitCode == 50)
        assertNotNull("Results should not be null", results)
        assertTrue("CloudFormation fixture should produce findings", results!!.results.isNotEmpty())
        println("✅ cloudformation: ${results.results.size} findings")
    }

    fun `test real scan on kubernetes fixture produces findings`() {
        val file = File(fixturesDir, "kubernetes-example.yaml").absolutePath
        val (exitCode, results, _) = runScan(file)
        assertTrue("Scan should succeed (got exit code $exitCode)", exitCode == 0 || exitCode == 50)
        assertNotNull("Results should not be null", results)
        assertTrue("Kubernetes fixture should produce findings", results!!.results.isNotEmpty())
        println("✅ kubernetes: ${results.results.size} findings")
    }

    fun `test real scan on Dockerfile fixture produces findings`() {
        val file = File(fixturesDir, "Dockerfile").absolutePath
        val (exitCode, results, _) = runScan(file)
        assertTrue("Scan should succeed (got exit code $exitCode)", exitCode == 0 || exitCode == 50)
        assertNotNull("Results should not be null", results)
        assertTrue("Dockerfile fixture should produce findings", results!!.results.isNotEmpty())
        println("✅ Dockerfile: ${results.results.size} findings")
    }

    fun `test real scan on ansible fixture produces findings`() {
        val file = File(fixturesDir, "ansible-playbook.yml").absolutePath
        val (exitCode, results, _) = runScan(file)
        assertTrue("Scan should succeed (got exit code $exitCode)", exitCode == 0 || exitCode == 50)
        assertNotNull("Results should not be null", results)
        assertTrue("Ansible fixture should produce findings", results!!.results.isNotEmpty())
        println("✅ ansible: ${results.results.size} findings")
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    fun `test scan results have correct shape`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (_, results, _) = runScan(file)
        assertNotNull(results)
        assertTrue("Should have findings", results!!.results.isNotEmpty())
        for (finding in results.results) {
            assertTrue("ruleId should not be blank", finding.ruleId.isNotBlank())
            assertTrue("ruleName should not be blank", finding.ruleName.isNotBlank())
            assertTrue("severity should not be blank", finding.severity.isNotBlank())
            assertTrue("filePath should not be blank", finding.filePath.isNotBlank())
            assertTrue("lineNumber should be positive", (finding.lineNumber ?: 0) > 0)
        }
    }

    fun `test severity counts are populated`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (_, results, _) = runScan(file)
        assertNotNull(results)
        assertTrue("totalIssues should be greater than 0", results!!.totalIssues > 0)
        val counted = results.criticalIssues + results.highIssues + results.mediumIssues +
            results.lowIssues + results.informationalIssues
        assertEquals("Severity counts should sum to totalIssues", results.totalIssues, counted)
    }

    fun `test filePath in results references the scanned file`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (_, results, _) = runScan(file)
        assertNotNull(results)
        assertTrue(
            "At least one finding should reference terraform-example.tf",
            results!!.results.any { it.filePath.contains("terraform-example.tf") }
        )
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    fun `test scan of nonexistent file exits non-zero`() {
        val (exitCode, _, _) = runScan("/tmp/does-not-exist-xyzzy.tf")
        assertTrue(
            "Scanning nonexistent file should fail (got exit code $exitCode)",
            exitCode != 0 && exitCode != 50
        )
    }

    // -------------------------------------------------------------------------
    // JSON Schema & Completeness
    // Expected fixed field lists — update these if the CLI format intentionally changes.
    // -------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private val expectedTopLevelFields = listOf(
        "fcs_version", "path", "project_name", "scan_type", "flags", "stats",
        "scan_uuid", "scan_performed_at", "scan_duration_seconds",
        "detection_summary", "rule_detections", "project_owners", "module_resolution"
    )
    private val expectedRuleDetectionFields = listOf(
        "rule_name", "rule_uuid", "rule_category", "description", "severity",
        "platform", "cloud_provider", "service", "rule_type", "detections"
    )
    private val expectedDetectionFields = listOf(
        "file", "file_sha256", "line", "resource_type", "resource_name",
        "issue_type", "reason", "recommendation"
    )
    // Optional fields that appear on some detections but not all
    private val optionalDetectionFields = listOf(
        "remediation", "remediation_type"  // added in CLI 4.1.0 — not yet used by the plugin
    )
    private val allKnownDetectionFields = (expectedDetectionFields + optionalDetectionFields).toSet()

    fun `test raw JSON has all expected top-level fields`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (exitCode, _, rawJson) = runScan(file)
        assertTrue("Scan should succeed", exitCode == 0 || exitCode == 50)
        assertNotNull("Raw JSON should be present", rawJson)
        val obj = json.parseToJsonElement(rawJson!!).jsonObject
        val actualFields = obj.keys.sorted()
        val expectedFields = expectedTopLevelFields.sorted()
        val added = actualFields.filter { it !in expectedTopLevelFields }
        val removed = expectedTopLevelFields.filter { it !in obj }
        assertEquals(
            "CLI JSON top-level fields changed. " +
            "Added: $added Removed: $removed. " +
            "Update expectedTopLevelFields and decide whether the plugins should use new fields.",
            expectedFields,
            actualFields
        )
    }

    fun `test rule_detections entries have all expected fields`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (exitCode, _, rawJson) = runScan(file)
        assertTrue("Scan should succeed", exitCode == 0 || exitCode == 50)
        assertNotNull(rawJson)
        val obj = json.parseToJsonElement(rawJson!!).jsonObject
        val ruleDetections = obj["rule_detections"]?.jsonArray
        assertNotNull("rule_detections should be present", ruleDetections)
        assertTrue("rule_detections should not be empty", ruleDetections!!.isNotEmpty())
        for (rd in ruleDetections) {
            val rdObj = rd.jsonObject
            val actualFields = rdObj.keys.sorted()
            val expectedFields = expectedRuleDetectionFields.sorted()
            val added = actualFields.filter { it !in expectedRuleDetectionFields }
            val removed = expectedRuleDetectionFields.filter { it !in rdObj }
            assertEquals(
                "rule_detection fields changed in rule \"${rdObj["rule_name"]}\". " +
                "Added: $added Removed: $removed. " +
                "Update expectedRuleDetectionFields and decide whether the plugins should use new fields.",
                expectedFields,
                actualFields
            )
        }
    }

    fun `test detections entries have all expected fields`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (exitCode, _, rawJson) = runScan(file)
        assertTrue("Scan should succeed", exitCode == 0 || exitCode == 50)
        assertNotNull(rawJson)
        val obj = json.parseToJsonElement(rawJson!!).jsonObject
        val ruleDetections = obj["rule_detections"]?.jsonArray ?: return
        for (rd in ruleDetections) {
            val detections = rd.jsonObject["detections"]?.jsonArray ?: continue
            for (det in detections) {
                val detObj = det.jsonObject
                // All required fields must be present
                for (field in expectedDetectionFields) {
                    assertTrue(
                        "Required detection field \"$field\" is missing in rule \"${rd.jsonObject["rule_name"]}\". " +
                        "Update expectedDetectionFields.",
                        detObj.containsKey(field)
                    )
                }
                // No field outside the known set (required + optional) should appear
                val unknown = detObj.keys.filter { it !in allKnownDetectionFields }
                assertEquals(
                    "Unknown detection fields in rule \"${rd.jsonObject["rule_name"]}\": $unknown. " +
                    "Add them to expectedDetectionFields or optionalDetectionFields and decide whether the plugins should use them.",
                    emptyList<String>(),
                    unknown
                )
            }
        }
    }

    fun `test parser maps every detection in raw JSON to a finding (no silent drops)`() {
        val file = File(fixturesDir, "terraform-example.tf").absolutePath
        val (exitCode, results, rawJson) = runScan(file)
        assertTrue("Scan should succeed", exitCode == 0 || exitCode == 50)
        assertNotNull(results)
        assertNotNull(rawJson)
        val obj = json.parseToJsonElement(rawJson!!).jsonObject
        val rawCount = obj["rule_detections"]?.jsonArray?.sumOf { rd ->
            rd.jsonObject["detections"]?.jsonArray?.size ?: 0
        } ?: 0
        assertEquals(
            "Parser returned ${results!!.results.size} findings but raw JSON contains $rawCount detections. " +
            "Some detections were silently dropped during parsing.",
            rawCount,
            results.results.size
        )
    }
}
