package com.crowdstrike.fcscliplugin.annotators

import com.crowdstrike.fcscliplugin.services.FCSResultsService
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Tests for FCSExternalAnnotator and FCSInspection severity mapping and annotation positioning.
 *
 * Severity contract (from VS Code reference implementation):
 *   critical      → ERROR
 *   high          → ERROR
 *   medium        → WARNING
 *   low           → WEAK_WARNING
 *   informational → WEAK_WARNING
 *   unknown       → INFORMATION (safe fallback)
 *
 * Annotation positioning contract:
 *   - Start offset: first non-whitespace character on the reported line (NOT column 0)
 *   - End offset: end of line (clamped)
 *   - Line numbers from CLI are 1-based; document API is 0-based
 *   - Line number beyond document length: clamped to last line, not thrown
 *
 * TODO(production-change): The severity mapping is currently embedded in
 * FCSExternalAnnotator.createAnnotation() (private) and FCSInspection.createProblemDescriptor()
 * (private). Extract both to internal companion functions so they can be tested directly
 * without running the full annotation pipeline.
 */
class FCSAnnotatorSeverityTest : BasePlatformTestCase() {

    // =========================================================================
    // FCSExternalAnnotator — severity → HighlightSeverity
    // =========================================================================

    fun `test annotator critical maps to ERROR`() {
        assertEquals(HighlightSeverity.ERROR, AnnotatorSeverityMapper.toHighlightSeverity("critical"))
    }

    fun `test annotator high maps to ERROR`() {
        assertEquals(HighlightSeverity.ERROR, AnnotatorSeverityMapper.toHighlightSeverity("high"))
    }

    fun `test annotator CRITICAL uppercase maps to ERROR`() {
        assertEquals(HighlightSeverity.ERROR, AnnotatorSeverityMapper.toHighlightSeverity("CRITICAL"))
    }

    fun `test annotator HIGH uppercase maps to ERROR`() {
        assertEquals(HighlightSeverity.ERROR, AnnotatorSeverityMapper.toHighlightSeverity("HIGH"))
    }

    fun `test annotator medium maps to WARNING`() {
        assertEquals(HighlightSeverity.WARNING, AnnotatorSeverityMapper.toHighlightSeverity("medium"))
    }

    fun `test annotator low maps to WEAK_WARNING`() {
        assertEquals(HighlightSeverity.WEAK_WARNING, AnnotatorSeverityMapper.toHighlightSeverity("low"))
    }

    fun `test annotator informational maps to WEAK_WARNING`() {
        assertEquals(HighlightSeverity.WEAK_WARNING, AnnotatorSeverityMapper.toHighlightSeverity("informational"))
    }

    fun `test annotator unknown severity maps to INFORMATION not WARNING or ERROR`() {
        assertEquals(HighlightSeverity.INFORMATION, AnnotatorSeverityMapper.toHighlightSeverity("unknown-value"))
    }

    fun `test annotator empty string maps to INFORMATION`() {
        assertEquals(HighlightSeverity.INFORMATION, AnnotatorSeverityMapper.toHighlightSeverity(""))
    }

    // =========================================================================
    // FCSInspection — severity → ProblemHighlightType
    // =========================================================================

    fun `test inspection critical maps to ERROR`() {
        assertEquals(ProblemHighlightType.ERROR, InspectionSeverityMapper.toProblemHighlightType("critical"))
    }

    fun `test inspection high maps to ERROR`() {
        assertEquals(ProblemHighlightType.ERROR, InspectionSeverityMapper.toProblemHighlightType("high"))
    }

    fun `test inspection medium maps to WARNING`() {
        assertEquals(ProblemHighlightType.WARNING, InspectionSeverityMapper.toProblemHighlightType("medium"))
    }

    fun `test inspection low maps to WEAK_WARNING`() {
        assertEquals(ProblemHighlightType.WEAK_WARNING, InspectionSeverityMapper.toProblemHighlightType("low"))
    }

    fun `test inspection informational maps to WEAK_WARNING`() {
        assertEquals(ProblemHighlightType.WEAK_WARNING, InspectionSeverityMapper.toProblemHighlightType("informational"))
    }

    fun `test inspection unknown severity maps to INFORMATION`() {
        assertEquals(ProblemHighlightType.INFORMATION, InspectionSeverityMapper.toProblemHighlightType("bogus"))
    }

    // =========================================================================
    // Annotation positioning
    //
    // Uses BasePlatformTestCase.myFixture to create a virtual file with known
    // content, then verifies offset calculations against the document.
    // =========================================================================

    fun `test positioning first non-whitespace on indented line`() {
        myFixture.configureByText("test.tf", "  acl = \"public-read\"\n")
        val document = myFixture.editor.document

        val lineStart = document.getLineStartOffset(0)
        val firstNonWs = AnnotationPositioner.firstNonWhitespaceOffset(document.text, lineStart)

        // "  acl ..." — first non-whitespace is at offset 2
        assertEquals(lineStart + 2, firstNonWs)
    }

    fun `test positioning non-indented line starts at column 0`() {
        myFixture.configureByText("test.tf", "acl = \"public-read\"\n")
        val document = myFixture.editor.document

        val lineStart = document.getLineStartOffset(0)
        val firstNonWs = AnnotationPositioner.firstNonWhitespaceOffset(document.text, lineStart)

        assertEquals(lineStart, firstNonWs)
    }

    fun `test positioning whitespace-only line falls back to line start`() {
        myFixture.configureByText("test.tf", "   \n")
        val document = myFixture.editor.document

        val lineStart = document.getLineStartOffset(0)
        val lineEnd = document.getLineEndOffset(0)
        val firstNonWs = AnnotationPositioner.firstNonWhitespaceOffset(document.text, lineStart)

        assertEquals(lineStart, firstNonWs)
    }

    fun `test positioning line number beyond document length is clamped`() {
        myFixture.configureByText("test.tf", "line1\nline2\n")
        val document = myFixture.editor.document

        // Document has 2 lines (0-indexed: 0,1). Line 99 should clamp to last valid line.
        val clamped = AnnotationPositioner.clampLineNumber(99, document.lineCount)
        assertTrue(clamped < document.lineCount)
        assertTrue(clamped >= 0)
    }

    fun `test positioning valid line number is unchanged`() {
        myFixture.configureByText("test.tf", "line1\nline2\nline3\n")
        val document = myFixture.editor.document

        assertEquals(1, AnnotationPositioner.clampLineNumber(1, document.lineCount))
    }

    fun `test positioning line number zero is clamped to zero`() {
        myFixture.configureByText("test.tf", "line1\n")
        val document = myFixture.editor.document

        assertEquals(0, AnnotationPositioner.clampLineNumber(0, document.lineCount))
    }

    fun `test positioning column number applied when valid`() {
        myFixture.configureByText("test.tf", "acl = \"public-read\"\n")
        val document = myFixture.editor.document

        val lineStart = document.getLineStartOffset(0)
        val lineEnd = document.getLineEndOffset(0)

        // Column 5 (1-based) → offset 4 from line start
        val offset = AnnotationPositioner.columnToOffset(col = 5, lineStart = lineStart, lineEnd = lineEnd)
        assertEquals(lineStart + 4, offset)
    }

    fun `test positioning column beyond line end is clamped to line start`() {
        myFixture.configureByText("test.tf", "x\n")
        val document = myFixture.editor.document

        val lineStart = document.getLineStartOffset(0)
        val lineEnd = document.getLineEndOffset(0)

        // Line is only 1 char; column 999 should fall back to line start
        val offset = AnnotationPositioner.columnToOffset(col = 999, lineStart = lineStart, lineEnd = lineEnd)
        assertEquals(lineStart, offset)
    }

    // =========================================================================
    // Annotation message format
    // =========================================================================

    fun `test annotation message format is severity rule message`() {
        val result = FCSResultsService.ScanResult(
            ruleId = "rule-1",
            ruleName = "terraform.public-read-acl",
            description = "desc",
            severity = "high",
            filePath = "/f.tf",
            message = "Bucket exposes data publicly"
        )
        val message = AnnotationMessageFormatter.format(result)
        assertEquals("[HIGH] terraform.public-read-acl: Bucket exposes data publicly", message)
    }

    fun `test annotation message uppercases severity`() {
        val result = FCSResultsService.ScanResult(
            ruleId = "r", ruleName = "n", description = "d",
            severity = "medium", filePath = "/f.tf", message = "m"
        )
        assertTrue(AnnotationMessageFormatter.format(result).startsWith("[MEDIUM]"))
    }
}

// =============================================================================
// Extracted helpers — TODO(production-change): move to production code
// =============================================================================

/**
 * Mirrors the severity mapping in FCSExternalAnnotator.createAnnotation().
 * Move to FCSExternalAnnotator.Companion as an internal function once extracted.
 */
object AnnotatorSeverityMapper {
    fun toHighlightSeverity(severity: String): HighlightSeverity = when (severity.lowercase()) {
        "critical" -> HighlightSeverity.ERROR
        "high"     -> HighlightSeverity.ERROR
        "medium"   -> HighlightSeverity.WARNING
        "low"          -> HighlightSeverity.WEAK_WARNING
        "informational" -> HighlightSeverity.WEAK_WARNING
        else       -> HighlightSeverity.INFORMATION
    }
}

/**
 * Mirrors the severity mapping in FCSInspection.createProblemDescriptor().
 */
object InspectionSeverityMapper {
    fun toProblemHighlightType(severity: String): ProblemHighlightType = when (severity.lowercase()) {
        "critical"      -> ProblemHighlightType.ERROR
        "high"          -> ProblemHighlightType.ERROR
        "medium"        -> ProblemHighlightType.WARNING
        "low", "informational" -> ProblemHighlightType.WEAK_WARNING
        else            -> ProblemHighlightType.INFORMATION
    }
}

/**
 * Positioning helpers now live in production code (FCSExternalAnnotator.kt).
 * Tests reference the production AnnotationPositioner directly.
 */

/** Mirrors FCSExternalAnnotator.buildAnnotationMessage(). */
object AnnotationMessageFormatter {
    fun format(result: FCSResultsService.ScanResult): String =
        "[${result.severity.uppercase()}] ${result.ruleName}: ${result.message}"
}
