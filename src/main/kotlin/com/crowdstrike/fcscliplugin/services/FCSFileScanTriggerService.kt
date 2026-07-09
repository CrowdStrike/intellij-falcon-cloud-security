package com.crowdstrike.fcscliplugin.services

import com.crowdstrike.fcscliplugin.FCSToolWindowFactory
import com.crowdstrike.fcscliplugin.annotators.FCSExternalAnnotator
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.application.ApplicationManager
import org.semver4j.Semver
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class FCSFileScanTriggerService(private val project: Project) {

    companion object {
        private val LOG = logger<FCSFileScanTriggerService>()
    }
    
    private val configService = project.getService(FCSConfigurationService::class.java)
    private val binaryService = project.getService(FCSBinaryService::class.java)
    private val resultsService = project.getService(FCSResultsService::class.java)
    
    // Track file modification times to avoid duplicate scans
    private val lastScanTimes = ConcurrentHashMap<String, Long>()
    
    // Virtual file listener to catch file save events
    private val fileListener = object : VirtualFileListener {
        override fun contentsChanged(event: VirtualFileEvent) {
            val file = event.file
            LOG.debug("File contents changed: ${file.path}")
            
            if (shouldTriggerScanOnSave(file)) {
                triggerScanForFile(file)
            }
        }
    }
    
    /**
     * Initialize the file listener
     */
    fun initialize() {
        LOG.info("Initializing FCS File Scan Trigger Service for project: ${project.name}")
        
        // Register the virtual file listener using the connection API to avoid deprecation
        VirtualFileManager.getInstance().addVirtualFileListener(fileListener, project)
        
        LOG.info("File save listener registered successfully")
    }
    
    /**
     * Check if a scan should be triggered when a file is saved
     */
    private fun shouldTriggerScanOnSave(file: VirtualFile): Boolean {
        // Check if plugin is enabled
        if (!configService.getPluginEnabled()) {
            LOG.debug("Plugin is disabled")
            return false
        }
        
        // Check if auto-scan on save is enabled
        if (!configService.getAutoScanOnSave()) {
            LOG.debug("Auto-scan on save is disabled")
            return false
        }
        
        // Check if FCS binary is available
        if (!binaryService.isFCSAvailable()) {
            LOG.debug("FCS binary is not available")
            return false
        }
        
        // Check if this is an IaC file
        if (!isIaCFile(file)) {
            LOG.debug("File ${file.path} is not an IaC file")
            return false
        }
        
        // Check if this is a scan result file (avoid scanning our own output)
        if (isScanResultFile(file)) {
            LOG.debug("File ${file.path} is a scan result file, skipping")
            return false
        }

        // Reject symlinks — the CLI follows them transparently and could scan files outside the workspace
        if (java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(file.path))) {
            LOG.debug("File ${file.path} is a symlink, skipping scan")
            return false
        }

        // Check if file is within the configured target path
        if (!configService.isFileWithinAnyScanPath(file.path)) {
            LOG.debug("File ${file.path} is not within the configured target path")
            return false
        }
        
        // Check if we've already scanned this file recently (within last 5 seconds)
        val filePath = file.path
        val currentTime = System.currentTimeMillis()
        val lastScanTime = lastScanTimes[filePath] ?: 0
        
        if (currentTime - lastScanTime < 5000) {
            LOG.debug("File $filePath was scanned recently, skipping duplicate scan")
            return false
        }
        
        return true
    }
    
    /**
     * Trigger a scan for a specific file
     */
    private fun triggerScanForFile(file: VirtualFile) {
        val filePath = file.path
        LOG.info("Triggering scan for file: $filePath")
        
        // Update last scan time
        lastScanTimes[filePath] = System.currentTimeMillis()
        
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            ApplicationManager.getApplication().invokeLater {
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project, "Scanning ${file.name} with FCS", true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        runScanForFile(filePath, file.name, indicator)
                    }
                })
            }
        } else {
            LOG.info("Skipping background scan in unit test mode")
        }
    }
    
    /**
     * Run the actual scan for a file
     */
    private fun runScanForFile(filePath: String, fileName: String, indicator: ProgressIndicator) {
        indicator.text = "Running FCS IaC scan on $fileName..."
        LOG.info("Starting background scan for file: $fileName")
        
        val binaryPath = binaryService.getFCSBinaryPath()
        if (binaryPath == null) {
            val msg = "❌ Scan skipped: FCS CLI not found. Click 'Download FCS CLI' in the FCS tool window."
            LOG.warn(msg)
            FCSToolWindowFactory.appendMessage(project, msg)
            return
        }

        val version = binaryService.getFCSVersion()
        if (version != null && !binaryService.isVersionCompatible(version)) {
            val msg = if (binaryService.isVersionAboveMaximum(version)) {
                "❌ Scan aborted: FCS CLI v$version is above the maximum validated version " +
                    "(${FCSBinaryService.MAXIMUM_CLI_VERSION}). Update the plugin to support this CLI version."
            } else {
                "❌ Scan aborted: FCS CLI v$version is below the minimum required version " +
                    "(${FCSBinaryService.MINIMUM_CLI_VERSION}). Click 'Download FCS CLI' to upgrade."
            }
            LOG.warn(msg)
            FCSToolWindowFactory.appendMessage(project, msg)
            return
        }
        
        try {
            // Clear any existing position updates for this file since we're getting new results
            resultsService.clearPositionUpdates(filePath)
            
            // Build and execute scan command
            val command = configService.buildIndividualFileScanCommand(binaryPath, filePath)
            LOG.info("Executing scan command: ${command.joinToString(" ")}")
            
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .also { pb ->
                    val proxy = System.getenv("HTTPS_PROXY")?.takeIf { it.isNotBlank() }
                    if (proxy != null) {
                        pb.environment()["HTTP_PROXY"] = proxy
                        pb.environment()["HTTPS_PROXY"] = proxy
                    }
                }
                .start()
            
            // Wait for completion
            val exitCode = process.waitFor()
            LOG.info("Scan completed with exit code: $exitCode for file: $fileName")

            // Read the process output for debugging
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (output.isNotEmpty()) {
                LOG.debug("Scan output: $output")
            }

            if (exitCode != 0 && exitCode != 50) {
                val cliOutput = output.trim().ifEmpty { "(no output)" }
                val msg = "❌ Scan failed for $fileName (exit code $exitCode):\n$cliOutput"
                LOG.warn(msg)
                FCSToolWindowFactory.appendMessage(project, msg)
                return
            }
            
            // Clean up old scan results to prevent disk space accumulation
            resultsService.cleanupOldResultsFromTemp(10)
            
            // Trigger re-annotation to pick up new results
            ApplicationManager.getApplication().invokeLater {
                LOG.info("Triggering daemon analyzer restart after scan completion for file: $fileName")
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
            }
            
        } catch (e: Exception) {
            LOG.error("Scan failed for file: $fileName", e)
            FCSToolWindowFactory.appendMessage(
                project,
                "❌ Scan failed for $fileName. Check the IDE log for details. Run 'fcs scan iac' from the terminal to diagnose."
            )
        }
    }
    
    /**
     * Check if a file is an IaC file that should be scanned
     */
    private fun isIaCFile(file: VirtualFile): Boolean {
        return FilePatternMatcher.matches(file.name, configService.getFilePatterns())
    }
    
    /**
     * Check if a file is a scan result file (to avoid scanning our own output)
     */
    private fun isScanResultFile(file: VirtualFile): Boolean {
        val fileName = file.name.lowercase()
        val filePath = file.path.lowercase()
        
        // Check if this is a scan results file
        val isProjectResultFile = (fileName.contains("scan-results") || 
                fileName.matches(Regex("\\d+-scan-results\\.json")) ||
                filePath.contains("/tmp/") && fileName.endsWith(".json")) &&
               fileName.endsWith(".json")
        
        // Also check if this file is in the temporary results directory
        val tempDir = System.getProperty("java.io.tmpdir").lowercase()
        val isTempResultFile = filePath.startsWith("$tempDir/fcs-scan-results") && 
                fileName.endsWith(".json")
        
        return isProjectResultFile || isTempResultFile
    }
    
    /**
     * Manually trigger a scan for a specific file (for external calls)
     */
    fun triggerManualScan(filePath: String) {
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
        if (virtualFile != null && isIaCFile(virtualFile)) {
            LOG.info("Manual scan triggered for file: $filePath")
            triggerScanForFile(virtualFile)
        } else {
            LOG.warn("Cannot trigger manual scan - file not found or not an IaC file: $filePath")
        }
    }
    
    /**
     * Check if auto-scan is enabled for this project
     */
    fun isAutoScanEnabled(): Boolean {
        return configService.getAutoScanOnSave()
    }
}

/**
 * Startup activity to initialize the file scan trigger service when project opens
 */
class FCSFileScanTriggerStartupActivity : ProjectActivity {

    companion object {
        private val LOG = logger<FCSFileScanTriggerStartupActivity>()
    }

    override suspend fun execute(project: Project) {
        LOG.info("Initializing FCS File Scan Trigger Service on project startup")

        val triggerService = project.getService(FCSFileScanTriggerService::class.java)
        triggerService.initialize()

        // Clean up old scan results on startup to prevent accumulation
        val resultsService = project.getService(FCSResultsService::class.java)
        try {
            // Primary cleanup - both scan types now use temp directory
            resultsService.cleanupOldResultsFromTemp(10)
            // Legacy cleanup - remove old project results if any exist
            resultsService.cleanupOldResults(10)
            LOG.info("Performed startup cleanup of old scan results")
        } catch (e: Exception) {
            LOG.warn("Failed to clean up old scan results on startup", e)
        }

        // Version upgrade detection and compatibility check
        val binaryService = project.getService(FCSBinaryService::class.java)
        val configService = project.getService(FCSConfigurationService::class.java)
        val currentVersion = binaryService.getFCSVersion() ?: return

        val lastVersion = configService.state.lastKnownCliVersion
        if (lastVersion.isNotBlank()) {
            try {
                val current = Semver(currentVersion)
                val last = Semver(lastVersion)
                if (current.isGreaterThan(last)) {
                    val migrated = binaryService.runMigrateConfig()
                    val msg = if (migrated)
                        "✅ FCS CLI upgraded to v$currentVersion. Config migrated automatically."
                    else
                        "⚠️ FCS CLI upgraded to v$currentVersion. Config migration may be needed — run 'fcs migrate-config' in your terminal if scans aren't working."
                    LOG.info(msg)
                    FCSToolWindowFactory.appendMessage(project, msg)
                }
            } catch (e: Exception) {
                LOG.warn("Version comparison failed during startup: ${e.message}")
            }
        }
        configService.state.lastKnownCliVersion = currentVersion

        if (!binaryService.isVersionCompatible(currentVersion)) {
            val msg = "⚠️ FCS CLI v$currentVersion is below the minimum required version " +
                "(${FCSBinaryService.MINIMUM_CLI_VERSION}). Click 'Download FCS CLI' to install the latest version."
            LOG.warn(msg)
            FCSToolWindowFactory.appendMessage(project, msg)
        }
    }
}
