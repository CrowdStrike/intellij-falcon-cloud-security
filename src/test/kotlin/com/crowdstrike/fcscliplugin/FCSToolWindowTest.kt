package com.crowdstrike.fcscliplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Method

/**
 * Tests for FCSToolWindowFactory — content creation, markdown conversion,
 * and HTML escaping.
 *
 * The tool window renders user-facing HTML via convertMarkdownToHtml().
 * escapeHtml() is a security boundary: any text that flows through it
 * and into the HTML renderer must not allow XSS via unescaped characters.
 *
 * convertMarkdownToHtml and escapeHtml are private on the inner FCSToolWindow
 * class — accessed via reflection until made internal.
 *
 * TODO(production-change): extract convertMarkdownToHtml and escapeHtml to a
 * companion object or standalone MarkdownRenderer so they are testable without
 * instantiating the full tool window (which requires a Project and UI).
 */
class FCSToolWindowTest : BasePlatformTestCase() {

    private lateinit var toolWindowInstance: Any
    private lateinit var convertMarkdownToHtml: Method
    private lateinit var escapeHtml: Method

    override fun setUp() {
        super.setUp()
        // Instantiate the inner FCSToolWindow class via reflection
        val innerClass = Class.forName("com.crowdstrike.fcscliplugin.FCSToolWindowFactory\$FCSToolWindow")
        val ctor = innerClass.getDeclaredConstructor(com.intellij.openapi.project.Project::class.java)
        ctor.isAccessible = true
        toolWindowInstance = ctor.newInstance(project)

        convertMarkdownToHtml = innerClass.getDeclaredMethod("convertMarkdownToHtml", String::class.java)
        convertMarkdownToHtml.isAccessible = true

        escapeHtml = innerClass.getDeclaredMethod("escapeHtml", String::class.java)
        escapeHtml.isAccessible = true
    }

    // -------------------------------------------------------------------------
    // shouldBeAvailable
    // -------------------------------------------------------------------------

    fun `test shouldBeAvailable always returns true`() {
        assertTrue(FCSToolWindowFactory().shouldBeAvailable(project))
    }

    // -------------------------------------------------------------------------
    // escapeHtml — security boundary
    // -------------------------------------------------------------------------

    fun `test escapeHtml escapes ampersand`() {
        assertEquals("a&amp;b", escape("a&b"))
    }

    fun `test escapeHtml escapes less-than`() {
        assertEquals("&lt;script&gt;", escape("<script>"))
    }

    fun `test escapeHtml escapes greater-than`() {
        assertEquals("a&gt;b", escape("a>b"))
    }

    fun `test escapeHtml escapes double quotes`() {
        assertEquals("&quot;value&quot;", escape("\"value\""))
    }

    fun `test escapeHtml escapes single quotes`() {
        assertEquals("it&#x27;s", escape("it's"))
    }

    fun `test escapeHtml plain text unchanged`() {
        assertEquals("hello world", escape("hello world"))
    }

    fun `test escapeHtml XSS payload is fully escaped`() {
        val xss = "<script>alert('xss')</script>"
        val result = escape(xss)
        assertFalse("Escaped output must not contain raw <script>", result.contains("<script>"))
        assertFalse("Escaped output must not contain raw </script>", result.contains("</script>"))
    }

    // -------------------------------------------------------------------------
    // convertMarkdownToHtml — structural correctness
    // -------------------------------------------------------------------------

    fun `test h1 markdown converts to h1 tag`() {
        val html = convert("# Title")
        assertTrue(html.contains("<h1>Title</h1>"))
    }

    fun `test h2 markdown converts to h2 tag`() {
        val html = convert("## Section")
        assertTrue(html.contains("<h2>Section</h2>"))
    }

    fun `test h3 markdown converts to h3 tag`() {
        val html = convert("### Subsection")
        assertTrue(html.contains("<h3>Subsection</h3>"))
    }

    fun `test bullet list converts to ul and li tags`() {
        val html = convert("- item one\n- item two")
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>"))
        assertTrue(html.contains("item one"))
        assertTrue(html.contains("item two"))
    }

    fun `test code block converts to pre tag`() {
        val html = convert("```\nsome code\n```")
        assertTrue(html.contains("<pre>"))
        assertTrue(html.contains("some code"))
        assertTrue(html.contains("</pre>"))
    }

    fun `test bold markdown converts to b tag`() {
        val html = convert("**bold text**")
        assertTrue(html.contains("<b>bold text</b>"))
    }

    fun `test inline code converts to tt tag`() {
        val html = convert("`code here`")
        assertTrue(html.contains("<tt>code here</tt>"))
    }

    fun `test html is wrapped in html and body tags`() {
        val html = convert("some content")
        assertTrue(html.startsWith("<html>"))
        assertTrue(html.contains("</html>"))
        assertTrue(html.contains("<body"))
        assertTrue(html.contains("<head>"))
    }

    fun `test html in markdown input is escaped not rendered`() {
        val html = convert("<b>not bold via injection</b>")
        assertFalse("Raw HTML in markdown must be escaped", html.contains("<b>not bold via injection</b>"))
    }

    fun `test empty string produces valid html skeleton`() {
        val html = convert("")
        assertTrue(html.contains("<html>"))
        assertTrue(html.contains("</html>"))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun escape(input: String): String =
        escapeHtml.invoke(toolWindowInstance, input) as String

    private fun convert(markdown: String): String =
        convertMarkdownToHtml.invoke(toolWindowInstance, markdown) as String
}
