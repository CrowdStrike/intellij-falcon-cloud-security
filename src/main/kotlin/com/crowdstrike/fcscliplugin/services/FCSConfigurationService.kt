package com.crowdstrike.fcscliplugin.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
@State(name = "FCSConfiguration", storages = [Storage("fcs-cli-plugin.xml")])
open class FCSConfigurationService(private val project: Project) : PersistentStateComponent<FCSConfigurationService.State> {

    companion object {
        private val LOG = logger<FCSConfigurationService>()

        val DEFAULT_FILE_PATTERNS: List<String> = listOf(
            "*.tf", "*.tfvars", "*.tfstate",
            "*.json", "*.yaml", "*.yml", "*.xml", "*.hcl", "*.rego",
            "*.dockerfile", "*.jinja",
            "*.bicep",
            "Dockerfile"
        )
    }

    data class State(
        var scanPaths: MutableList<String> = mutableListOf(),
        var outputPath: String = "tmp",
        var selectedSeverities: MutableSet<String> = mutableSetOf(),
        var excludeSecrets: Boolean = false,
        var uploadResults: Boolean = false,
        var customBinaryPath: String = "",
        var autoScanOnSave: Boolean = true,
        var pluginEnabled: Boolean = true,
        var filePatterns: MutableList<String> = mutableListOf(),
        var scanTimeout: Int = 300,
        var platforms: MutableList<String> = mutableListOf(),
        var lastKnownCliVersion: String = ""
    )
    
    private var state = State()
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }
    
    // Scan paths configuration — empty list defaults to project root
    open fun getScanPaths(): List<String> {
        return if (state.scanPaths.isEmpty()) {
            listOf(project.basePath ?: System.getProperty("user.dir"))
        } else {
            state.scanPaths
        }
    }

    fun setScanPaths(paths: List<String>) {
        state.scanPaths = paths.toMutableList()
    }

    // Keep single-path accessor for command building convenience
    open fun getTargetPath(): String = getScanPaths().first()
    
    // Output path configuration
    fun getOutputPath(): String {
        val basePath = project.basePath ?: System.getProperty("user.dir")
        return if (state.outputPath.isEmpty()) {
            Paths.get(basePath, "tmp").toString()
        } else {
            if (Paths.get(state.outputPath).isAbsolute) {
                state.outputPath
            } else {
                Paths.get(basePath, state.outputPath).toString()
            }
        }
    }
    
    fun setOutputPath(path: String) {
        state.outputPath = path
    }
    
    // Severity level configuration
    fun getSelectedSeverities(): Set<SeverityLevel> {
        return if (state.selectedSeverities.isEmpty()) {
            // Empty means all severities selected
            SeverityLevel.values().toSet()
        } else {
            state.selectedSeverities.mapNotNull { severityName ->
                try {
                    SeverityLevel.valueOf(severityName.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toSet()
        }
    }
    
    fun setSelectedSeverities(severities: Set<SeverityLevel>) {
        state.selectedSeverities = severities.map { it.name.lowercase() }.toMutableSet()
    }
    
    fun isAllSeveritiesSelected(): Boolean {
        return state.selectedSeverities.isEmpty() || state.selectedSeverities.size == SeverityLevel.values().size
    }

    // Returns all severity levels at or above the lowest selected level.
    // Selecting "high" yields {high, critical}; selecting "medium" yields {medium, high, critical}.
    // When all severities are selected (or none), returns the full set.
    fun getEffectiveSeverities(): Set<SeverityLevel> {
        if (isAllSeveritiesSelected()) return SeverityLevel.values().toSet()
        val threshold = getSelectedSeverities().minByOrNull { it.ordinal }
            ?: return SeverityLevel.values().toSet()
        return SeverityLevel.values().filter { it.ordinal >= threshold.ordinal }.toSet()
    }
    
    // Exclude secrets configuration
    fun getExcludeSecrets(): Boolean = state.excludeSecrets
    
    fun setExcludeSecrets(exclude: Boolean) {
        state.excludeSecrets = exclude
    }
    
    // Upload results configuration
    fun getUploadResults(): Boolean = state.uploadResults
    
    fun setUploadResults(upload: Boolean) {
        state.uploadResults = upload
    }
    
    // Custom binary path configuration
    fun getCustomBinaryPath(): String = state.customBinaryPath

    fun setCustomBinaryPath(path: String) {
        state.customBinaryPath = path
    }

    // File patterns — empty list means use DEFAULT_FILE_PATTERNS
    fun getFilePatterns(): List<String> {
        return if (state.filePatterns.isEmpty()) DEFAULT_FILE_PATTERNS else state.filePatterns
    }

    fun setFilePatterns(patterns: List<String>) {
        state.filePatterns = patterns.toMutableList()
    }

    fun getScanTimeout(): Int = state.scanTimeout

    fun setScanTimeout(timeout: Int) {
        state.scanTimeout = timeout
    }

    fun getPlatforms(): List<String> = state.platforms

    fun setPlatforms(platforms: List<String>) {
        state.platforms = platforms.toMutableList()
    }

    val validPlatforms: List<String> = listOf(
        "Ansible", "AzureResourceManager", "CloudFormation", "Crossplane",
        "DockerCompose", "Dockerfile", "GoogleDeploymentManager", "Kubernetes",
        "OpenAPI", "Pulumi", "ServerlessFW", "Terraform"
    )
    
    // Auto-scan on save configuration
    fun getAutoScanOnSave(): Boolean = state.autoScanOnSave
    
    fun setAutoScanOnSave(autoScan: Boolean) {
        state.autoScanOnSave = autoScan
    }
    
    // Plugin enabled configuration
    fun getPluginEnabled(): Boolean = state.pluginEnabled
    
    fun setPluginEnabled(enabled: Boolean) {
        state.pluginEnabled = enabled
    }
    
    // Build FCS scan command arguments for project-wide scanning
    fun buildScanCommand(binaryPath: String): List<String> {
        val command = mutableListOf<String>()
        
        command.add(binaryPath)
        command.add("scan")
        command.add("iac")
        
        // Add target path — comma-separated when multiple paths configured
        command.add("--path")
        command.add(getScanPaths().joinToString(","))
        
        // Add output directory (use temporary path for consistency with individual file scans)
        command.add("--output-path")
        command.add(getTemporaryOutputPath())
        
        // Add policy rule (required)
        command.add("--policy-rule")
        command.add("local")
        
        // Add severities only if specific ones are selected (not all)
        // LOW is not a valid CLI argument — map it to informational (lowest valid level)
        if (!isAllSeveritiesSelected()) {
            val effectiveSeverities = getEffectiveSeverities()
                .map { if (it == SeverityLevel.LOW) SeverityLevel.INFORMATIONAL else it }
                .toSet()
            if (effectiveSeverities.isNotEmpty()) {
                command.add("--severities")
                command.add(effectiveSeverities.joinToString(",") { it.name.lowercase() })
            }
        }

        // Add exclude secrets flag
        if (getExcludeSecrets()) {
            command.add("--disable-secrets-scan")
        }

        // Add upload results flag
        if (getUploadResults()) {
            command.add("--upload")
        }

        // Add fail-on flag to avoid exit code 40
        command.add("--fail-on")
        command.add("critical=100,high=100,medium=100,informational=100")

        command.add("--timeout")
        command.add(getScanTimeout().toString())

        val platforms = getPlatforms()
        if (platforms.isNotEmpty()) {
            command.add("--platforms")
            command.add(platforms.joinToString(","))
        }

        return command
    }
    // Build FCS scan command arguments for individual file scanning
    fun buildIndividualFileScanCommand(binaryPath: String, filePath: String): List<String> {
        LOG.info("Building individual file scan command for file: $filePath")
        val command = mutableListOf<String>()

        command.add(binaryPath)
        command.add("scan")
        command.add("iac")

        // Scan individual file instead of entire project
        command.add("--path")
        command.add(filePath)

        // Use system temporary directory for output
        val tempOutputPath = getTemporaryOutputPath()
        command.add("--output-path")
        command.add(tempOutputPath)
        LOG.info("Using temporary output path: $tempOutputPath")

        // Add policy rule (required)
        command.add("--policy-rule")
        command.add("local")

        // Add severities only if specific ones are selected (not all)
        // LOW is not a valid CLI argument — map it to informational (lowest valid level)
        if (!isAllSeveritiesSelected()) {
            val effectiveSeverities = getEffectiveSeverities()
                .map { if (it == SeverityLevel.LOW) SeverityLevel.INFORMATIONAL else it }
                .toSet()
            if (effectiveSeverities.isNotEmpty()) {
                command.add("--severities")
                command.add(effectiveSeverities.joinToString(",") { it.name.lowercase() })
            }
        }

        // Add exclude secrets flag
        if (getExcludeSecrets()) {
            command.add("--disable-secrets-scan")
        }

        // Add upload results flag
        if (getUploadResults()) {
            command.add("--upload")
        }

        // Add fail-on flag to avoid exit code 40
        command.add("--fail-on")
        command.add("critical=100,high=100,medium=100,informational=100")

        command.add("--timeout")
        command.add(getScanTimeout().toString())

        val platforms = getPlatforms()
        if (platforms.isNotEmpty()) {
            command.add("--platforms")
            command.add(platforms.joinToString(","))
        }

        LOG.info("Built command: ${command.joinToString(" ")}")
        return command
    }
    
    // Get temporary output path for individual file scans
    open fun getTemporaryOutputPath(): String {
        val tempDir = System.getProperty("java.io.tmpdir")
        val tempOutputPath = Paths.get(tempDir, "fcs-scan-results")
        
        // Create the directory if it doesn't exist
        try {
            if (!java.nio.file.Files.exists(tempOutputPath)) {
                java.nio.file.Files.createDirectories(tempOutputPath)
                LOG.info("Created temporary output directory: $tempOutputPath")
            }
            // Ensure the directory is writable
            if (!java.nio.file.Files.isWritable(tempOutputPath)) {
                LOG.warn("Temporary output directory is not writable: $tempOutputPath")
            }
        } catch (e: Exception) {
            LOG.error("Failed to create temporary output directory: $tempOutputPath", e)
        }
        
        return tempOutputPath.toString()
    }
    
    // Check if a file is within any of the configured scan paths
    fun isFileWithinAnyScanPath(filePath: String): Boolean {
        val normalizedFilePath = try {
            Paths.get(filePath).toRealPath()
        } catch (e: Exception) {
            LOG.warn("Could not resolve real path for file '$filePath', rejecting scan")
            return false
        }

        for (scanPath in getScanPaths()) {
            try {
                val normalizedScanPath = try {
                    Paths.get(scanPath).toRealPath()
                } catch (e: Exception) {
                    LOG.warn("Could not resolve real path for scan path '$scanPath', skipping")
                    continue
                }
                if (normalizedFilePath.startsWith(normalizedScanPath)) {
                    return true
                }
            } catch (e: Exception) {
                LOG.error("Error checking file '$filePath' against scan path '$scanPath'", e)
            }
        }
        return false
    }
    
    enum class SeverityLevel {
        INFORMATIONAL, LOW, MEDIUM, HIGH, CRITICAL
    }
}
