package com.crowdstrike.fcscliplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.semver4j.Semver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
class FCSBinaryService(private val project: Project) {

    companion object {
        private val LOG = logger<FCSBinaryService>()
        const val MINIMUM_CLI_VERSION = "3.0.0"
        const val MAXIMUM_CLI_VERSION = "4.2.1"
    }

    private var cachedBinaryPath: String? = null
    private var lastCheckTime: Long = 0
    private val cacheValidityDuration = 30_000L // 30 seconds
    
    /**
     * Check if FCS binary is available on the system
     */
    fun isFCSAvailable(): Boolean {
        return getFCSBinaryPath() != null
    }
    
    /**
     * Get the path to the FCS binary, with caching
     */
    fun getFCSBinaryPath(): String? {
        val currentTime = System.currentTimeMillis()
        
        // Use cached result if still valid
        if (cachedBinaryPath != null && (currentTime - lastCheckTime) < cacheValidityDuration) {
            return cachedBinaryPath
        }
        
        // Refresh the binary path
        cachedBinaryPath = findFCSBinary()
        lastCheckTime = currentTime
        
        return cachedBinaryPath
    }
    
    /**
     * Force refresh of the FCS binary detection
     */
    fun refreshBinaryPath(): String? {
        cachedBinaryPath = null
        lastCheckTime = 0
        return getFCSBinaryPath()
    }
    
    /**
     * Get FCS version information
     */
    fun getFCSVersion(): String? {
        val binaryPath = getFCSBinaryPath() ?: return null
        
        return try {
            val process = ProcessBuilder(binaryPath, "version")
                .redirectErrorStream(true)
                .start()
            
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
            
            // Add timeout to prevent hanging - wait max 10 seconds
            val exitCode = if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly() // Kill the process if it times out
                -1 // Return error code for timeout
            }
            
            if (exitCode == 0) output.trim().removePrefix("fcs version: ") else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Find FCS binary using multiple detection methods
     */
    private fun findFCSBinary(): String? {
        // Method 1: Check downloaded FCS location FIRST (prioritize latest download)
        val downloadPath = System.getProperty("user.home") + "/.local/bin/fcs"
        if (isValidFCSBinary(downloadPath)) {
            return downloadPath
        }
        
        // Method 2: Try 'which fcs' command
        val whichResult = tryWhichCommand()
        if (whichResult != null) return whichResult
        
        // Method 3: Check other common installation paths
        val commonPaths = listOf(
            "/usr/local/bin/fcs",
            "/usr/bin/fcs",
            "/opt/crowdstrike/fcs/bin/fcs",
            System.getProperty("user.home") + "/bin/fcs"
        )
        
        for (path in commonPaths) {
            if (isValidFCSBinary(path)) {
                return path
            }
        }
        
        // Method 4: Check PATH environment variable
        val pathEnv = System.getenv("PATH") ?: return null
        val pathDirs = pathEnv.split(System.getProperty("path.separator"))

        for (dir in pathDirs) {
            val fcsPath = Paths.get(dir, "fcs").toString()
            if (!isInsideAnyWorkspace(fcsPath) && isValidFCSBinary(fcsPath)) {
                return fcsPath
            }
        }

        return null
    }

    /**
     * Returns true if the given path falls inside the current project's root directory.
     * Used to reject workspace-local binaries that could shadow the real FCS installation.
     */
    private fun isInsideAnyWorkspace(resolvedPath: String): Boolean {
        return try {
            val normalizedPath = Paths.get(resolvedPath).toAbsolutePath().normalize()
            val basePath = project.basePath ?: return false
            val projectRoot = Paths.get(basePath).toAbsolutePath().normalize()
            normalizedPath.startsWith(projectRoot)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Try to find FCS using the 'which' command
     */
    private fun tryWhichCommand(): String? {
        return try {
            val process = ProcessBuilder("which", "fcs")
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }

            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty() && !isInsideAnyWorkspace(output) && isValidFCSBinary(output)) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Returns true if the given version string is >= MINIMUM_CLI_VERSION.
     */
    fun isVersionCompatible(version: String): Boolean {
        if (version.isBlank()) return false
        return try {
            val v = Semver(version)
            v.isGreaterThanOrEqualTo(MINIMUM_CLI_VERSION) && v.isLowerThanOrEqualTo(MAXIMUM_CLI_VERSION)
        } catch (e: Exception) {
            false
        }
    }

    fun isVersionAboveMaximum(version: String): Boolean {
        if (version.isBlank()) return false
        return try {
            Semver(version).isGreaterThan(MAXIMUM_CLI_VERSION)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs `fcs migrate-config` only when the legacy v2 config file exists.
     * Running unconditionally on a v3 user (who has fcs.json but not fcs_profiles.json)
     * would create a blank fcs.json and wipe their credentials.
     * Returns true if migration ran and exited 0, false if skipped or failed.
     */
    fun runMigrateConfig(): Boolean {
        val legacyConfig = Paths.get(System.getProperty("user.home"), ".crowdstrike", "fcs_profiles.json")
        if (!java.nio.file.Files.exists(legacyConfig)) return false

        val binaryPath = getFCSBinaryPath() ?: return false
        return try {
            val process = ProcessBuilder(binaryPath, "migrate-config")
                .redirectErrorStream(true)
                .start()
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Exception) {
            LOG.warn("fcs migrate-config failed: ${e.message}")
            false
        }
    }

    /**
     * Validate that a path points to a valid FCS binary
     */
    private fun isValidFCSBinary(path: String): Boolean {
        return try {
            val file = Paths.get(path)
            if (!java.nio.file.Files.exists(file) || !java.nio.file.Files.isExecutable(file)) {
                return false
            }
            
            // Try to execute the binary with version to verify it's FCS
            val process = ProcessBuilder(path, "version")
                .redirectErrorStream(true)
                .start()
            
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
            
            // Add timeout to prevent hanging - wait max 10 seconds
            val exitCode = if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly() // Kill the process if it times out
                -1 // Return error code for timeout
            }
            
            exitCode == 0 && output.lowercase().contains("fcs")
        } catch (e: Exception) {
            false
        }
    }
}
