package com.crowdstrike.fcscliplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class FCSResultsService(private val project: Project) {
    
    companion object {
        private val LOG = logger<FCSResultsService>()
    }
    
    private val configService = project.getService(FCSConfigurationService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    @Serializable
    data class ScanResult(
        val ruleId: String,
        val ruleName: String,
        val description: String,
        val severity: String,
        val filePath: String,
        val lineNumber: Int? = null,
        val columnNumber: Int? = null,
        val resource: String? = null,
        val message: String
    )
    
    @Serializable
    data class ScanResults(
        val results: List<ScanResult>,
        val scanTime: String,
        val exitCode: Int,
        val totalIssues: Int,
        val criticalIssues: Int,
        val highIssues: Int,
        val mediumIssues: Int,
        val lowIssues: Int,
        val informationalIssues: Int,
        val scannedFiles: Int
    )
    
    data class ScanResultFile(
        val file: File,
        val scanTime: LocalDateTime,
        val results: ScanResults?
    )
    
    /**
     * Get all scan result files from the output directory
     */
    fun getScanResultFiles(): List<ScanResultFile> {
        val outputPath = Paths.get(configService.getOutputPath())
        if (!Files.exists(outputPath) || !Files.isDirectory(outputPath)) {
            return emptyList()
        }
        
        return try {
            Files.list(outputPath)
                .filter { it.toString().endsWith(".json") }
                .map { path ->
                    val file = path.toFile()
                    val lastModified = Instant.ofEpochMilli(file.lastModified())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                    
                    val results = try {
                        parseResultsFile(file)
                    } catch (e: Exception) {
                        null
                    }
                    
                    ScanResultFile(file, lastModified, results)
                }
                .sorted { a, b -> b.scanTime.compareTo(a.scanTime) } // Most recent first
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get all scan result files from the temporary directory (for individual file scans)
     */
    fun getScanResultFilesFromTemp(): List<ScanResultFile> {
        val tempOutputPath = Paths.get(configService.getTemporaryOutputPath())
        if (!Files.exists(tempOutputPath) || !Files.isDirectory(tempOutputPath)) {
            return emptyList()
        }
        
        return try {
            Files.list(tempOutputPath)
                .filter { it.toString().endsWith(".json") }
                .map { path ->
                    val file = path.toFile()
                    val lastModified = Instant.ofEpochMilli(file.lastModified())
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                    
                    val results = try {
                        parseResultsFile(file)
                    } catch (e: Exception) {
                        null
                    }
                    
                    ScanResultFile(file, lastModified, results)
                }
                .sorted { a, b -> b.scanTime.compareTo(a.scanTime) } // Most recent first
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get the most recent scan results from project directory
     */
    fun getLatestResults(): ScanResultFile? {
        return getScanResultFiles().firstOrNull()
    }
    
    /**
     * Get the most recent scan results from temporary directory (individual file scans)
     */
    fun getLatestResultsFromTemp(): ScanResultFile? {
        return getScanResultFilesFromTemp().firstOrNull()
    }
    
    /**
     * Parse a scan results JSON file
     */
    fun parseResultsFile(file: File): ScanResults {
        val content = file.readText()
        return parseResultsJson(content)
    }
    
    /**
     * Parse scan results from JSON string
     */
    fun parseResultsJson(jsonContent: String): ScanResults {
        try {
            val jsonElement = json.parseToJsonElement(jsonContent)
            val jsonObj = jsonElement.jsonObject
            
            // Extract scan time from the actual field
            val scanTime = jsonObj["scan_performed_at"]?.jsonPrimitive?.content 
                ?: LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            
            var exitCode = 0
            var scannedFiles = 0
            
            // Extract file stats
            val fileStats = jsonObj["stats"]?.jsonObject?.get("files_stats")?.jsonObject
            if (fileStats != null) {
                scannedFiles = fileStats["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            }
            
            val results = mutableListOf<ScanResult>()
            var totalIssues = 0
            var criticalIssues = 0
            var highIssues = 0
            var mediumIssues = 0
            var lowIssues = 0
            var informationalIssues = 0
            
            // Parse rule_detections array (new FCS format)
            val ruleDetections = jsonObj["rule_detections"]?.jsonArray
            if (ruleDetections != null) {
                ruleDetections.forEach { ruleElement ->
                    try {
                        val ruleObj = ruleElement.jsonObject
                        val ruleName = ruleObj["rule_name"]?.jsonPrimitive?.content ?: "Unknown Rule"
                        val ruleId = ruleObj["rule_uuid"]?.jsonPrimitive?.content ?: "unknown"
                        val description = ruleObj["description"]?.jsonPrimitive?.content ?: ""
                        val severity = ruleObj["severity"]?.jsonPrimitive?.content?.lowercase() ?: "unknown"
                        
                        // Parse detections for this rule
                        val detections = ruleObj["detections"]?.jsonArray ?: return@forEach
                        detections.forEach { detectionElement ->
                            try {
                                val detectionObj = detectionElement.jsonObject
                                val filePath = detectionObj["file"]?.jsonPrimitive?.content ?: ""
                                val line = detectionObj["line"]?.jsonPrimitive?.content?.toIntOrNull()
                                val resourceType = detectionObj["resource_type"]?.jsonPrimitive?.content
                                val resourceName = detectionObj["resource_name"]?.jsonPrimitive?.content
                                val reason = detectionObj["reason"]?.jsonPrimitive?.content ?: ""
                                val recommendation = detectionObj["recommendation"]?.jsonPrimitive?.content ?: ""
                                
                                // Build resource info for display
                                val resource = if (resourceType != null && resourceName != null) {
                                    "$resourceType: $resourceName"
                                } else {
                                    resourceType ?: resourceName
                                }
                                
                                // Normalize file path - remove relative path components
                                val normalizedFilePath = normalizeFilePath(filePath)
                                
                                val result = ScanResult(
                                    ruleId = ruleId,
                                    ruleName = ruleName,
                                    description = description,
                                    severity = severity,
                                    filePath = normalizedFilePath,
                                    lineNumber = line,
                                    columnNumber = null, // Not available in this format
                                    resource = resource,
                                    message = reason.ifEmpty { recommendation }
                                )
                                
                                results.add(result)
                                totalIssues++
                                
                                when (severity) {
                                    "critical" -> criticalIssues++
                                    "high" -> highIssues++
                                    "medium" -> mediumIssues++
                                    "low" -> lowIssues++
                                    "informational" -> informationalIssues++
                                }
                            } catch (e: Exception) {
                                // Skip malformed detection entries
                            }
                        }
                    } catch (e: Exception) {
                        // Skip malformed rule entries
                    }
                }
            }
            
            // Use detection_summary if available for more accurate counts
            val detectionSummary = jsonObj["detection_summary"]?.jsonObject
            if (detectionSummary != null) {
                totalIssues = detectionSummary["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: totalIssues
                criticalIssues = detectionSummary["critical"]?.jsonPrimitive?.content?.toIntOrNull() ?: criticalIssues
                highIssues = detectionSummary["high"]?.jsonPrimitive?.content?.toIntOrNull() ?: highIssues
                mediumIssues = detectionSummary["medium"]?.jsonPrimitive?.content?.toIntOrNull() ?: mediumIssues
                lowIssues = detectionSummary["low"]?.jsonPrimitive?.content?.toIntOrNull() ?: lowIssues
                informationalIssues = detectionSummary["informational"]?.jsonPrimitive?.content?.toIntOrNull() ?: informationalIssues
            }

            return ScanResults(
                results = results,
                scanTime = scanTime,
                exitCode = exitCode,
                totalIssues = totalIssues,
                criticalIssues = criticalIssues,
                highIssues = highIssues,
                mediumIssues = mediumIssues,
                lowIssues = lowIssues,
                informationalIssues = informationalIssues,
                scannedFiles = scannedFiles
            )
            
        } catch (e: Exception) {
            // Return empty results on parse error
            return ScanResults(
                results = emptyList(),
                scanTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                exitCode = -1,
                totalIssues = 0,
                criticalIssues = 0,
                highIssues = 0,
                mediumIssues = 0,
                lowIssues = 0,
                informationalIssues = 0,
                scannedFiles = 0
            )
        }
    }
    
    /**
     * Normalize file paths from scan results to match project files
     */
    private fun normalizeFilePath(rawFilePath: String): String {
        try {
            val path = Paths.get(rawFilePath)
            
            // If it's already absolute, return as-is
            if (path.isAbsolute) {
                return path.normalize().toString()
            }
            
            // Handle relative paths with ../ components
            val normalizedPath = path.normalize()
            var pathStr = normalizedPath.toString().replace("\\", "/")
            
            // Remove any remaining ../ components from the beginning
            while (pathStr.startsWith("../")) {
                pathStr = pathStr.substring(3)
            }
            
            // If the path is empty after normalization, just use the filename
            if (pathStr.isEmpty()) {
                return path.fileName?.toString() ?: rawFilePath
            }
            
            return pathStr
            
        } catch (e: Exception) {
            // Fallback: just return the filename
            return Paths.get(rawFilePath).fileName?.toString() ?: rawFilePath
        }
    }
    
    /**
     * Get results for a specific file path, prioritizing temporary individual file scan results
     */
    fun getResultsForFile(filePath: String): List<ScanResult> {
        // Check temporary results first (for individual file scans)
        val tempResults = getLatestResultsFromTemp()?.results
        if (tempResults != null) {
            val matchingTempResults = tempResults.results.filter { result ->
                isPathMatch(filePath, result.filePath)
            }
            if (matchingTempResults.isNotEmpty()) {
                return matchingTempResults
            }
        }
        
        // Fall back to project-wide results if no temp results found
        val latest = getLatestResults()?.results ?: return emptyList()
        
        val matchingResults = latest.results.filter { result ->
            isPathMatch(filePath, result.filePath)
        }
        
        return matchingResults
    }
    
    /**
     * Check if two file paths match using multiple strategies
     */
    private fun isPathMatch(targetPath: String, resultPath: String): Boolean {
        if (targetPath.isEmpty() || resultPath.isEmpty()) return false
        try {
            // Clean paths
            val targetCleaned = targetPath.replace("\\", "/")
            val resultCleaned = resultPath.replace("\\", "/")
            
            // Strategy 1: Exact match
            if (targetCleaned == resultCleaned) {
                return true
            }
            
            // Strategy 2: Filename match
            val targetFileName = Paths.get(targetPath).fileName?.toString() ?: ""
            val resultFileName = Paths.get(resultPath).fileName?.toString() ?: ""
            if (targetFileName.isNotEmpty() && targetFileName == resultFileName) {
                return true
            }
            
            // Strategy 3: One path ends with the other (handles relative vs absolute paths)
            if (targetCleaned.endsWith(resultCleaned) || resultCleaned.endsWith(targetCleaned)) {
                return true
            }
            
            // Strategy 4: Both paths contain the same relative project path
            val targetNormalized = Paths.get(targetPath).normalize().toString().replace("\\", "/")
            val resultNormalized = Paths.get(resultPath).normalize().toString().replace("\\", "/")
            
            // Look for common suffixes that include project structure
            val targetParts = targetNormalized.split("/").filter { it.isNotEmpty() }
            val resultParts = resultNormalized.split("/").filter { it.isNotEmpty() }
            
            // Find the longest common suffix
            var commonSuffixLength = 0
            val minLength = minOf(targetParts.size, resultParts.size)
            
            for (i in 1..minLength) {
                val targetPart = targetParts[targetParts.size - i]
                val resultPart = resultParts[resultParts.size - i]
                if (targetPart == resultPart) {
                    commonSuffixLength = i
                } else {
                    break
                }
            }
            
            // If we have at least 2 matching components (e.g., "fcs-cli-plugin/main.tf"), consider it a match
            if (commonSuffixLength >= 2) {
                return true
            }
            
            return false
            
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Open a file in the editor at a specific line
     */
    fun navigateToFile(filePath: String, lineNumber: Int? = null) {
        try {
            val file = if (Paths.get(filePath).isAbsolute) {
                VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
            } else {
                val basePath = project.basePath ?: return
                val fullPath = Paths.get(basePath, filePath).normalize().toString()
                VirtualFileManager.getInstance().findFileByUrl("file://$fullPath")
            }
            
            file?.let { virtualFile ->
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(virtualFile, true)
                
                // Navigate to specific line if provided
                lineNumber?.let { line ->
                    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .getSelectedTextEditor()
                    
                    editor?.let { e ->
                        val document = e.document
                        if (line > 0 && line <= document.lineCount) {
                            val offset = document.getLineStartOffset(line - 1)
                            e.caretModel.moveToOffset(offset)
                            e.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore navigation errors
        }
    }
    
    /**
     * Delete old result files, keeping only the most recent N files
     */
    fun cleanupOldResults(keepCount: Int = 10) {
        val allFiles = getScanResultFiles()
        if (allFiles.size > keepCount) {
            val filesToDelete = allFiles.drop(keepCount)
            filesToDelete.forEach { resultFile ->
                try {
                    resultFile.file.delete()
                    LOG.debug("Deleted old scan result file: ${resultFile.file.name}")
                } catch (e: Exception) {
                    LOG.warn("Failed to delete old scan result file: ${resultFile.file.name}", e)
                }
            }
            LOG.info("Cleaned up ${filesToDelete.size} old scan result files, kept $keepCount most recent")
        }
    }
    
    /**
     * Delete old result files from temporary directory, keeping only the most recent N files
     */
    fun cleanupOldResultsFromTemp(keepCount: Int = 10) {
        val allFiles = getScanResultFilesFromTemp()
        if (allFiles.size > keepCount) {
            val filesToDelete = allFiles.drop(keepCount)
            filesToDelete.forEach { resultFile ->
                try {
                    resultFile.file.delete()
                    LOG.debug("Deleted old temporary scan result file: ${resultFile.file.name}")
                } catch (e: Exception) {
                    LOG.warn("Failed to delete old temporary scan result file: ${resultFile.file.name}", e)
                }
            }
            LOG.info("Cleaned up ${filesToDelete.size} old temporary scan result files, kept $keepCount most recent")
        }
    }
    
    // In-memory storage for position updates
    private val positionUpdates = mutableMapOf<String, MutableMap<String, PositionUpdate>>()
    
    data class PositionUpdate(
        val newLineNumber: Int,
        val newColumnNumber: Int?
    )
    
    /**
     * Update the position of a scan result based on document changes
     */
    fun updateResultPosition(filePath: String, resultId: String, newLineNumber: Int, newColumnNumber: Int?) {
        val normalizedPath = normalizeFilePath(filePath)
        positionUpdates.computeIfAbsent(normalizedPath) { mutableMapOf() }[resultId] = 
            PositionUpdate(newLineNumber, newColumnNumber)
    }
    
    /**
     * Get results for a specific file path with updated positions
     */
    fun getUpdatedResultsForFile(filePath: String): List<ScanResult> {
        LOG.info("getUpdatedResultsForFile called for: $filePath")
        
        val baseResults = getResultsForFile(filePath)
        LOG.info("Base results count: ${baseResults.size}")
        
        val normalizedPath = normalizeFilePath(filePath)
        val updates = positionUpdates[normalizedPath] ?: return baseResults.also {
            LOG.info("No position updates found, returning ${it.size} base results")
        }
        
        val updatedResults = baseResults.map { result ->
            val resultId = "${result.ruleId}_${result.lineNumber}_${result.message.hashCode()}"
            val update = updates[resultId]
            
            if (update != null) {
                LOG.debug("Applied position update for result: $resultId")
                result.copy(
                    lineNumber = update.newLineNumber,
                    columnNumber = update.newColumnNumber
                )
            } else {
                result
            }
        }
        
        LOG.info("Returning ${updatedResults.size} results with position updates")
        return updatedResults
    }
    
    /**
     * Clear position updates for a file (typically when new scan results are available)
     */
    fun clearPositionUpdates(filePath: String) {
        val normalizedPath = normalizeFilePath(filePath)
        positionUpdates.remove(normalizedPath)
    }
    
    /**
     * Generate a unique identifier for a scan result
     */
    fun generateResultId(result: ScanResult): String {
        return "${result.ruleId}_${result.lineNumber}_${result.message.hashCode()}"
    }
}
