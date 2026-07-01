package com.crowdstrike.fcscliplugin.inspections

import com.crowdstrike.fcscliplugin.services.FCSResultsService
import com.intellij.analysis.AnalysisScope
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.*
import com.intellij.codeInspection.reference.RefElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class FCSInspection : GlobalInspectionTool() {
    
    override fun getDisplayName(): String = "FCS IaC Security Issues"
    
    override fun getShortName(): String = "FCSIaCIssues"
    
    override fun getGroupDisplayName(): String = "Security"
    
    override fun getDefaultLevel(): HighlightDisplayLevel = HighlightDisplayLevel.ERROR
    
    override fun runInspection(
        scope: AnalysisScope,
        manager: InspectionManager,
        globalContext: GlobalInspectionContext,
        problemDescriptorsProcessor: ProblemDescriptionsProcessor
    ) {
        val project = globalContext.project
        val resultsService = project.service<FCSResultsService>()
        
        try {
            // Get all results from the latest scan
            val latestScanFile = resultsService.getLatestResults()
            val allResults = latestScanFile?.results?.results ?: emptyList()
            
            for (result in allResults) {
                // Convert file path to VirtualFile
                val filePath = result.filePath
                val virtualFile = findVirtualFile(project, filePath) ?: continue
                
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: continue
                
                createProblemDescriptor(
                    manager,
                    psiFile,
                    result,
                    problemDescriptorsProcessor,
                    globalContext
                )
            }
        } catch (e: Exception) {
            // Handle any errors gracefully
        }
    }
    
    private fun findVirtualFile(project: Project, filePath: String): VirtualFile? {
        return try {
            val file = File(filePath)
            val projectBasePath = project.basePath ?: return null
            val projectBase = File(projectBasePath)
            
            // Get relative path from project root
            val relativePath = file.relativeTo(projectBase).path.replace('\\', '/')
            project.baseDir?.findFileByRelativePath(relativePath)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun createProblemDescriptor(
        manager: InspectionManager,
        psiFile: PsiFile,
        result: FCSResultsService.ScanResult,
        processor: ProblemDescriptionsProcessor,
        globalContext: GlobalInspectionContext
    ) {
        val highlightType = when (result.severity.lowercase()) {
            "critical" -> ProblemHighlightType.ERROR
            "high" -> ProblemHighlightType.ERROR
            "medium" -> ProblemHighlightType.WARNING
            "low", "informational" -> ProblemHighlightType.WEAK_WARNING
            else -> ProblemHighlightType.INFORMATION
        }
        
        // Find the element at the specified line
        val element = try {
            val document = psiFile.viewProvider.document
            if (document != null && result.lineNumber != null && result.lineNumber > 0 && result.lineNumber <= document.lineCount) {
                val lineStartOffset = document.getLineStartOffset(result.lineNumber - 1)
                val lineEndOffset = document.getLineEndOffset(result.lineNumber - 1)
                
                // Try to find a more specific element if column is available
                val targetOffset = result.columnNumber?.let { col ->
                    if (col > 0 && lineStartOffset + col - 1 <= lineEndOffset) {
                        lineStartOffset + col - 1
                    } else {
                        lineStartOffset
                    }
                } ?: lineStartOffset
                
                psiFile.findElementAt(targetOffset) ?: psiFile
            } else {
                psiFile
            }
        } catch (e: Exception) {
            psiFile
        }
        
        val message = "CrowdStrike FCS: [${result.severity.uppercase()}] ${result.ruleName}: ${result.message}"
        
        val problemDescriptor = manager.createProblemDescriptor(
            element,
            message,
            null as LocalQuickFix?,
            highlightType,
            true
        )
        
        processor.addProblemElement(
            globalContext.refManager.getReference(psiFile),
            problemDescriptor
        )
    }
}
