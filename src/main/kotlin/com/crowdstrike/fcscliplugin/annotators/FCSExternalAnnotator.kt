package com.crowdstrike.fcscliplugin.annotators

import com.crowdstrike.fcscliplugin.services.FCSBinaryService
import com.crowdstrike.fcscliplugin.services.FCSConfigurationService
import com.crowdstrike.fcscliplugin.services.FCSResultsService
import com.intellij.openapi.diagnostic.logger
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

class FCSExternalAnnotator : ExternalAnnotator<FCSExternalAnnotator.CollectedInfo, FCSExternalAnnotator.AnnotationResult>() {

    companion object {
        private val LOG = logger<FCSExternalAnnotator>()

        // Storage for persistent annotation tracking across document changes
        private val fileAnnotationCache = ConcurrentHashMap<String, MutableList<EnhancedAnnotationInfo>>()
        private val documentListeners = ConcurrentHashMap<String, FCSDocumentListener>()
    }
    
    data class CollectedInfo(
        val project: Project,
        val file: VirtualFile,
        val filePath: String,
        val fileContent: String,
        val lastModified: Long,
        val editor: Editor?
    )
    
    data class AnnotationResult(
        val issues: List<FCSResultsService.ScanResult>,
        val shouldRunNewScan: Boolean
    )
    
    // Enhanced data structure to track semantic information with RangeMarkers
    data class EnhancedAnnotationInfo(
        val issue: FCSResultsService.ScanResult,
        val rangeMarker: RangeMarker,
        val elementType: String,
        val elementIdentifier: String,
        val parentContext: String?
    )
    
    // Document listener to track real-time changes
    private class FCSDocumentListener(
        private val filePath: String, 
        private val project: Project
    ) : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            ApplicationManager.getApplication().invokeLater {
                updateAnnotationPositions(event)
            }
        }
        
        private fun updateAnnotationPositions(event: DocumentEvent) {
            val annotations = fileAnnotationCache[filePath] ?: return
            val document = event.document
            val resultsService = project.getService(FCSResultsService::class.java)
            
            // Collect invalid annotations to remove after iteration
            val invalidAnnotations = mutableListOf<EnhancedAnnotationInfo>()
            
            // Update positions for all valid range markers
            annotations.forEach { annotationInfo ->
                val rangeMarker = annotationInfo.rangeMarker
                
                if (rangeMarker.isValid) {
                    // Calculate new line and column from range marker position
                    val startOffset = rangeMarker.startOffset
                    val newLineNumber = document.getLineNumber(startOffset) + 1 // Convert to 1-based
                    val lineStartOffset = document.getLineStartOffset(document.getLineNumber(startOffset))
                    val newColumnNumber = startOffset - lineStartOffset + 1 // Convert to 1-based
                    
                    // Generate result ID to match the scan result
                    val resultId = resultsService.generateResultId(annotationInfo.issue)
                    
                    // Update the scan result position
                    resultsService.updateResultPosition(
                        filePath = filePath,
                        resultId = resultId,
                        newLineNumber = newLineNumber,
                        newColumnNumber = newColumnNumber
                    )
                } else {
                    // Mark invalid range markers for removal
                    invalidAnnotations.add(annotationInfo)
                }
            }
            
            // Remove all invalid annotations after iteration is complete
            if (invalidAnnotations.isNotEmpty()) {
                annotations.removeAll(invalidAnnotations)
            }
        }
    }

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): CollectedInfo? {
        val virtualFile = file.virtualFile ?: return null
        val project = file.project
        
        // Check if this is an IaC file that should be annotated
        if (!isIaCFile(virtualFile, project)) {
            return null
        }
        
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val filePath = virtualFile.path
        val fileContent = document.text
        val lastModified = virtualFile.timeStamp
        
        // Register document listener if not already registered
        if (!documentListeners.containsKey(filePath)) {
            val listener = FCSDocumentListener(filePath, project)
            document.addDocumentListener(listener)
            documentListeners[filePath] = listener
        }
        
        return CollectedInfo(project, virtualFile, filePath, fileContent, lastModified, editor)
    }
    
    override fun doAnnotate(collectedInfo: CollectedInfo?): AnnotationResult? {
        if (collectedInfo == null) return null
        
        LOG.info("doAnnotate called for file: ${collectedInfo.filePath}")
        
        val resultsService = collectedInfo.project.getService(FCSResultsService::class.java)
        val binaryService = collectedInfo.project.getService(FCSBinaryService::class.java)
        val configService = collectedInfo.project.getService(FCSConfigurationService::class.java)
        
        // Get results for the current file with updated positions, filtered by current severity threshold
        val allFileIssues = resultsService.getUpdatedResultsForFile(collectedInfo.filePath)
        val effectiveSeverities = configService.getEffectiveSeverities().map { it.name.lowercase() }.toSet()
        val fileIssues = allFileIssues.filter { it.severity.lowercase() in effectiveSeverities }
        LOG.info("Found ${allFileIssues.size} issues for file (${fileIssues.size} after severity filter): ${collectedInfo.filePath}")
        
        // Check if plugin is enabled
        if (!configService.getPluginEnabled()) {
            LOG.debug("Plugin is disabled, hiding all annotations")
            return AnnotationResult(emptyList(), false) // Hide all annotations when plugin is disabled
        }
        
        // Check if we should trigger a new scan (only for files that have never been scanned)
        var shouldRunNewScan = false
        
        if (binaryService.isFCSAvailable()) {
            // Check if file is within the configured target path
            if (configService.isFileWithinAnyScanPath(collectedInfo.filePath)) {
                // Only trigger scan if no previous scan results exist for this specific file (first time opening file)
                // File save events are now handled by FCSFileScanTriggerService
                val hasFileResults = fileIssues.isNotEmpty()
                
                if (!hasFileResults) {
                    shouldRunNewScan = true
                    LOG.info("Will trigger initial scan for file: ${collectedInfo.filePath} (no previous results for this file)")
                } else {
                    LOG.info("File ${collectedInfo.filePath} has existing results, file save scans are handled by FCSFileScanTriggerService")
                }
            } else {
                LOG.info("File ${collectedInfo.filePath} is not within configured target path, skipping initial scan")
            }
        }
        
        return AnnotationResult(fileIssues, shouldRunNewScan)
    }
    
    override fun apply(
        file: PsiFile,
        annotationResult: AnnotationResult?,
        holder: AnnotationHolder
    ) {
        if (annotationResult == null) return
        
        // Trigger new scan if needed
        if (annotationResult.shouldRunNewScan) {
            triggerBackgroundScan(file.project, file.virtualFile.path)
        }
        
        // Apply annotations for existing issues
        annotationResult.issues.forEach { issue ->
            createAnnotation(file, issue, holder)
        }
    }
    
    private fun createAnnotation(
        file: PsiFile,
        issue: FCSResultsService.ScanResult,
        holder: AnnotationHolder
    ) {
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile) ?: return
        val filePath = file.virtualFile?.path ?: return
        
        // Determine the line number (1-based from scan results, 0-based for document)
        val lineNumber = (issue.lineNumber ?: 1) - 1
        if (lineNumber < 0 || lineNumber >= document.lineCount) {
            return // Invalid line number
        }
        
        // Get the line range
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        
        // Use column number if available, otherwise start at first non-whitespace character
        val startOffset = issue.columnNumber?.let { col ->
            AnnotationPositioner.columnToOffset(col, lineStartOffset, lineEndOffset)
        } ?: AnnotationPositioner.firstNonWhitespaceOffset(document.text, lineStartOffset)
        
        val endOffset = minOf(lineEndOffset, startOffset + 100) // Limit highlight length
        
        // Create RangeMarker for persistent tracking across document changes
        val rangeMarker = document.createRangeMarker(startOffset, endOffset).apply {
            setGreedyToLeft(true)
            setGreedyToRight(true)
        }
        
        // Extract semantic information from PSI element at the location
        val elementInfo = extractSemanticInformation(file, startOffset, issue)
        
        // Store enhanced annotation info for persistent tracking
        val enhancedInfo = EnhancedAnnotationInfo(
            issue = issue,
            rangeMarker = rangeMarker,
            elementType = elementInfo.elementType,
            elementIdentifier = elementInfo.identifier,
            parentContext = elementInfo.parentContext
        )
        
        // Add to cache for tracking across document changes
        fileAnnotationCache.computeIfAbsent(filePath) { mutableListOf() }.add(enhancedInfo)
        
        val textRange = TextRange(startOffset, endOffset)
        
        // Determine annotation severity based on issue severity
        val severity = when (issue.severity.lowercase()) {
            "critical" -> HighlightSeverity.ERROR
            "high" -> HighlightSeverity.ERROR
            "medium" -> HighlightSeverity.WARNING
            "low" -> HighlightSeverity.WEAK_WARNING
            "informational" -> HighlightSeverity.WEAK_WARNING
            else -> HighlightSeverity.INFORMATION
        }

        // Create the annotation with highlighting
        val message = buildAnnotationMessage(issue)
        val annotation = holder.newAnnotation(severity, message)
            .range(textRange)
        
        // Add tooltip with enhanced information including semantic context
        val tooltip = buildEnhancedTooltipMessage(issue, elementInfo)
        annotation.tooltip(tooltip)
        
        annotation.create()
    }
    
    // Data class for semantic element information
    private data class ElementSemanticInfo(
        val elementType: String,
        val identifier: String,
        val parentContext: String?
    )
    
    private fun extractSemanticInformation(
        file: PsiFile,
        offset: Int,
        issue: FCSResultsService.ScanResult
    ): ElementSemanticInfo {
        val psiElement = file.findElementAt(offset)
        
        // Extract semantic information based on the PSI element
        val elementType = when {
            issue.resource != null -> "resource"
            psiElement?.text?.contains(Regex("\\w+\\s*=")) == true -> "attribute"
            psiElement?.text?.contains("{") == true -> "block"
            else -> "element"
        }
        
        // Extract identifier (resource name, attribute name, etc.)
        val identifier = issue.resource 
            ?: psiElement?.text?.substringBefore("=")?.trim()
            ?: psiElement?.text?.take(50)
            ?: "unknown"
        
        // Extract parent context (containing block, resource, etc.)
        val parentContext = psiElement?.parent?.let { parent ->
            // Look for containing resource or block
            var current = parent
            while (current != null && current !is PsiFile) {
                val text = current.text
                if (text.contains(Regex("resource\\s+\"\\w+\"\\s+\"\\w+\""))) {
                    // Found a Terraform resource
                    return@let text.substringAfter("resource").substringBefore("{").trim()
                }
                current = current.parent
            }
            null
        }
        
        return ElementSemanticInfo(elementType, identifier, parentContext)
    }
    
    private fun buildEnhancedTooltipMessage(
        issue: FCSResultsService.ScanResult,
        elementInfo: ElementSemanticInfo
    ): String {
        val sb = StringBuilder()
        sb.append("<html><body>")
        sb.append("<b>CrowdStrike FCS</b><br><br>")
        sb.append("<b>Rule:</b> ${issue.ruleName}<br>")
        sb.append("<b>Severity:</b> ${issue.severity.uppercase()}<br>")
        sb.append("<b>Rule ID:</b> ${issue.ruleId}<br>")
        
        // Add semantic information
        sb.append("<b>Element Type:</b> ${elementInfo.elementType}<br>")
        sb.append("<b>Element:</b> ${elementInfo.identifier}<br>")
        
        if (elementInfo.parentContext != null) {
            sb.append("<b>Context:</b> ${elementInfo.parentContext}<br>")
        }
        
        if (issue.resource != null) {
            sb.append("<b>Resource:</b> ${issue.resource}<br>")
        }
        
        if (issue.lineNumber != null) {
            sb.append("<b>Line:</b> ${issue.lineNumber}<br>")
        }
        
        sb.append("<br><b>Description:</b><br>")
        sb.append(issue.description.replace("\n", "<br>"))
        
        if (issue.message != issue.description) {
            sb.append("<br><br><b>Message:</b><br>")
            sb.append(issue.message.replace("\n", "<br>"))
        }
        
        sb.append("</body></html>")
        return sb.toString()
    }
    
    private fun buildAnnotationMessage(issue: FCSResultsService.ScanResult): String {
        return "CrowdStrike FCS: [${issue.severity.uppercase()}] ${issue.ruleName}: ${issue.message}"
    }
    
    private fun triggerBackgroundScan(project: Project, filePath: String) {
        LOG.info("triggerBackgroundScan called for file: $filePath")
        
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            ApplicationManager.getApplication().invokeLater {
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project, "Running FCS IaC Scan", true
                ) {
                    override fun run(indicator: ProgressIndicator) {
                        val fileName = Paths.get(filePath).fileName?.toString() ?: "file"
                        indicator.text = "Running FCS IaC scan on $fileName..."
                        LOG.info("Starting background scan for file: $fileName")
                        
                        val binaryService = project.getService(FCSBinaryService::class.java)
                        val configService = project.getService(FCSConfigurationService::class.java)
                        
                        val binaryPath = binaryService.getFCSBinaryPath()
                        if (binaryPath == null) {
                            LOG.warn("FCS binary path is null, cannot run scan")
                            return // Can't run scan without binary
                        }
                        
                        try {
                            // Use individual file scanning instead of project-wide
                            val command = configService.buildIndividualFileScanCommand(binaryPath, filePath)
                            LOG.info("Executing scan command: ${command.joinToString(" ")}")
                            
                            val process = ProcessBuilder(command)
                                .redirectErrorStream(true)
                                .start()
                            
                            // Wait for completion
                            val exitCode = process.waitFor()
                            LOG.info("Scan completed with exit code: $exitCode for file: $fileName")
                            
                            // Read the process output for debugging
                            val output = process.inputStream.bufferedReader().use { it.readText() }
                            if (output.isNotEmpty()) {
                                LOG.info("Scan output: $output")
                            }
                            
                            // Refresh temporary results directory and trigger re-annotation
                            ApplicationManager.getApplication().invokeLater {
                                LOG.info("Triggering daemon analyzer restart after scan completion")
                                // Trigger re-annotation to pick up new results
                                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                            }
                            
                        } catch (e: Exception) {
                            LOG.error("Scan failed for file: $fileName", e)
                            // Scan failed, but don't show error to user during auto-scan
                        }
                    }
                })
            }
        } else {
            LOG.info("Skipping background scan in unit test mode")
        }
    }
    
    private fun isIaCFile(virtualFile: VirtualFile, project: Project): Boolean {
        if (isScanResultFile(virtualFile)) return false
        val configService = project.getService(FCSConfigurationService::class.java)
        return com.crowdstrike.fcscliplugin.services.FilePatternMatcher.matches(
            virtualFile.name, configService.getFilePatterns()
        )
    }
    
    private fun isScanResultFile(virtualFile: VirtualFile): Boolean {
        val fileName = virtualFile.name.lowercase()
        val filePath = virtualFile.path.lowercase()

        // Check if this is a scan results file
        // Scan results files are typically named with timestamps and are in the output directory
        val isProjectResultFile = (fileName.contains("scan-results") ||
                fileName.matches(Regex("\\d+-scan-results\\.json")) ||
                filePath.contains("/tmp/") && fileName.endsWith(".json")) &&
               fileName.endsWith(".json")

        // Also check if this file is in the temporary results directory (individual file scans)
        val tempDir = System.getProperty("java.io.tmpdir").lowercase()
        val isTempResultFile = filePath.startsWith("$tempDir/fcs-scan-results") &&
                fileName.endsWith(".json")

        return isProjectResultFile || isTempResultFile
    }
}

internal object AnnotationPositioner {

    fun firstNonWhitespaceOffset(text: String, from: Int, fallback: Int = from): Int {
        var i = from
        while (i < text.length && text[i] != '\n' && text[i].isWhitespace()) i++
        return if (i < text.length && text[i] != '\n') i else fallback
    }

    fun clampLineNumber(line: Int, lineCount: Int): Int = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))

    fun columnToOffset(col: Int, lineStart: Int, lineEnd: Int): Int {
        val offset = lineStart + col - 1
        return if (col > 0 && offset <= lineEnd) offset else lineStart
    }
}
