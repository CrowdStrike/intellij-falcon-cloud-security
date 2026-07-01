package com.crowdstrike.fcscliplugin

import com.crowdstrike.fcscliplugin.services.FCSBinaryService
import com.crowdstrike.fcscliplugin.services.FCSConfigurationService
import com.crowdstrike.fcscliplugin.services.FCSResultsService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Color
import java.awt.Font
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer

class FCSToolWindowFactory : ToolWindowFactory {

    companion object {
        private val LOG = logger<FCSToolWindowFactory>()
        private val outputAreas = ConcurrentHashMap<Project, JTextArea>()

        fun appendMessage(project: Project, message: String) {
            ApplicationManager.getApplication().invokeLater {
                val area = outputAreas[project] ?: return@invokeLater
                area.append("$message\n")
                area.caretPosition = area.document.length
            }
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val fcsToolWindow = FCSToolWindow(project)
        val content = ContentFactory.getInstance().createContent(fcsToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    class FCSToolWindow(private val project: Project) {
        private val binaryService = project.getService(FCSBinaryService::class.java)
        private val configService = project.getService(FCSConfigurationService::class.java)
        private val resultsService = project.getService(FCSResultsService::class.java)

        private val statusLabel = JBLabel()
        private val versionLabel = JBLabel()
        private val refreshButton = JButton("Refresh Status")
        private val downloadButton = JButton("Download FCS CLI")
        private val downloadLinkLabel = JBLabel()
        private val outputArea = JTextArea(10, 50)

        private val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(createTopPanel(), BorderLayout.NORTH)
            add(createTabbedPane(), BorderLayout.CENTER)

            // Initialize the UI
            updateStatus()
        }

        init {
            outputAreas[project] = outputArea
        }

        fun getContent(): JBPanel<JBPanel<*>> = content

        private fun createTabbedPane(): JTabbedPane {
            val tabbedPane = JTabbedPane()
            
            // Add Configuration tab
            val configurationTab = createConfigurationTab()
            tabbedPane.addTab("Configuration", configurationTab)
            
            // Add Documentation tab
            val documentationTab = createDocumentationTab()
            tabbedPane.addTab("Documentation", documentationTab)
            
            return tabbedPane
        }

        private fun createConfigurationTab(): JPanel {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())

            val container = JBPanel<JBPanel<*>>()
            container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
            container.add(createStatusPanel())
            container.add(createConfigurationPanel())

            outputArea.isEditable = false
            outputArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            val outputPanel = JBPanel<JBPanel<*>>(BorderLayout())
            outputPanel.border = BorderFactory.createTitledBorder("Output")
            outputPanel.add(JScrollPane(outputArea), BorderLayout.CENTER)
            outputPanel.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 200)
            container.add(outputPanel)

            val scrollPane = JScrollPane(container)
            scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            panel.add(scrollPane, BorderLayout.CENTER)
            return panel
        }

        private fun createDocumentationTab(): JPanel {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            
            val documentationContent = createDocumentationContent()
            val scrollPane = JScrollPane(documentationContent)
            scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            
            // Ensure the scroll pane starts at the top
            SwingUtilities.invokeLater {
                scrollPane.viewport.viewPosition = java.awt.Point(0, 0)
            }
            
            panel.add(scrollPane, BorderLayout.CENTER)
            return panel
        }

        private fun createDocumentationContent(): JPanel {
            val panel = JBPanel<JBPanel<*>>(BorderLayout())
            
            try {
                // Read markdown content from resources
                val markdownContent = loadDocumentationMarkdown()
                val htmlContent = convertMarkdownToHtml(markdownContent)
                
                // Create text pane for better HTML rendering
                val textPane = javax.swing.JTextPane()
                textPane.contentType = "text/html"
                textPane.text = htmlContent
                textPane.isEditable = false
                textPane.background = panel.background
                textPane.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)
                
                panel.add(textPane, BorderLayout.CENTER)
                
            } catch (e: Exception) {
                // Fallback content if markdown file cannot be loaded
                val errorPanel = JBPanel<JBPanel<*>>(BorderLayout())
                val errorLabel = JBLabel("<html><h2>Documentation Error</h2><p>Could not load documentation content.</p><p>Error: ${e.message}</p><p>Class loader: ${javaClass.classLoader}</p><p>Resource URL: ${javaClass.getResource("/documentation.md")}</p></html>")
                errorLabel.border = BorderFactory.createEmptyBorder(15, 15, 15, 15)
                errorPanel.add(errorLabel, BorderLayout.CENTER)
                panel.add(errorPanel, BorderLayout.CENTER)
            }
            
            return panel
        }
        
        private fun loadDocumentationMarkdown(): String {
            // Try multiple approaches to load the file
            
            // Approach 1: Standard resource loading
            val inputStream1 = javaClass.getResourceAsStream("/documentation.md")
            if (inputStream1 != null) {
                return inputStream1.bufferedReader().use { it.readText() }
            }
            
            // Approach 2: Class loader resource loading
            val inputStream2 = javaClass.classLoader.getResourceAsStream("documentation.md")
            if (inputStream2 != null) {
                return inputStream2.bufferedReader().use { it.readText() }
            }
            
            // Approach 3: Thread context class loader
            val inputStream3 = Thread.currentThread().contextClassLoader.getResourceAsStream("documentation.md")
            if (inputStream3 != null) {
                return inputStream3.bufferedReader().use { it.readText() }
            }
            
            throw IllegalStateException("Documentation file not found in any location")
        }
        
        private fun convertMarkdownToHtml(markdown: String): String {
            val html = StringBuilder()
            html.append("<html><head><style>")
            html.append("body { font-family: Arial, Helvetica, sans-serif; margin: 8px; }")
            html.append("h1 { margin: 6px 0 2px 0; font-size: 1.3em; }")
            html.append("h2 { margin: 6px 0 2px 0; font-size: 1.1em; }")
            html.append("h3 { margin: 4px 0 2px 0; font-size: 1.0em; }")
            html.append("p { margin: 2px 0; }")
            html.append("ul, ol { margin: 2px 0; padding-left: 20px; }")
            html.append("li { margin: 1px 0; }")
            html.append("hr { border: none; height: 1px; background-color: #ccc; margin: 6px 0; }")
            html.append("pre { margin: 4px 0; padding: 4px; }")
            html.append("</style></head><body>")
            
            val lines = markdown.lines()
            var inCodeBlock = false
            var inList = false
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                when {
                    // Code blocks - simplified without complex CSS
                    trimmedLine.startsWith("```") -> {
                        if (inCodeBlock) {
                            html.append("</pre>")
                            inCodeBlock = false
                        } else {
                            if (inList) {
                                html.append("</ul>")
                                inList = false
                            }
                            html.append("<pre>")
                            inCodeBlock = true
                        }
                    }
                    
                    inCodeBlock -> {
                        html.append(escapeHtml(line)).append("\n")
                    }
                    
                    // Horizontal rules
                    trimmedLine == "---" -> {
                        if (inList) {
                            html.append("</ul>")
                            inList = false
                        }
                        html.append("<hr/>")
                    }
                    
                    // Headers - using simple HTML without complex CSS
                    trimmedLine.startsWith("# ") -> {
                        if (inList) {
                            html.append("</ul>")
                            inList = false
                        }
                        html.append("<h1>")
                            .append(escapeHtml(trimmedLine.substring(2)))
                            .append("</h1>")
                    }
                    
                    trimmedLine.startsWith("## ") -> {
                        if (inList) {
                            html.append("</ul>")
                            inList = false
                        }
                        html.append("<h2>")
                            .append(escapeHtml(trimmedLine.substring(3)))
                            .append("</h2>")
                    }
                    
                    trimmedLine.startsWith("### ") -> {
                        if (inList) {
                            html.append("</ul>")
                            inList = false
                        }
                        html.append("<h3>")
                            .append(escapeHtml(trimmedLine.substring(4)))
                            .append("</h3>")
                    }
                    
                    // Lists - simple HTML
                    trimmedLine.startsWith("- ") -> {
                        if (!inList) {
                            html.append("<ul>")
                            inList = true
                        }
                        html.append("<li>")
                            .append(formatInlineMarkdown(trimmedLine.substring(2)))
                            .append("</li>")
                    }
                    
                    trimmedLine.matches(Regex("\\d+\\. .*")) -> {
                        if (!inList) {
                            html.append("<ol>")
                            inList = true
                        }
                        html.append("<li>")
                            .append(formatInlineMarkdown(trimmedLine.substring(trimmedLine.indexOf(". ") + 2)))
                            .append("</li>")
                    }
                    
                    // Empty lines
                    trimmedLine.isEmpty() -> {
                        if (inList) {
                            html.append("</ul>")
                            inList = false
                        }
                        html.append("<br/>")
                    }
                    
                    // Regular paragraphs
                    else -> {
                        if (inList) {
                            html.append("</ul>")
                            inList = false
                        }
                        html.append("<p>")
                            .append(formatInlineMarkdown(trimmedLine))
                            .append("</p>")
                    }
                }
            }
            
            if (inList) {
                html.append("</ul>")
            }
            if (inCodeBlock) {
                html.append("</pre>")
            }
            
            html.append("</body></html>")
            return html.toString()
        }
        
        private fun formatInlineMarkdown(text: String): String {
            var result = escapeHtml(text)
            
            // Images ![alt text](url)
            result = result.replace(Regex("!\\[([^\\]]*)\\]\\(([^\\)]+)\\)")) { matchResult ->
                val altText = matchResult.groupValues[1]
                val url = matchResult.groupValues[2]
                
                // Convert to resource URL for the plugin
                val resourceUrl = javaClass.getResource("/$url")
                val imageUrl = resourceUrl?.toString() ?: url
                
                "<img src='$imageUrl' alt='$altText' style='max-width: 100px; height: auto; margin: 10px 0;'/>"
            }
            
            // Bold text **text** - remove complex CSS
            result = result.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            
            // Italic text *text*
            result = result.replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
            
            // Inline code `code` - simplified
            result = result.replace(Regex("`(.*?)`"), "<tt>$1</tt>")
            
            return result
        }
        
        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")
        }

        private fun createTopPanel(): JPanel {
            val panel = JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
            
            // Plugin enabled checkbox at the very top, left-aligned
            val pluginEnabledCheck = JCheckBox("Plugin Enabled", configService.getPluginEnabled())
            pluginEnabledCheck.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            panel.add(pluginEnabledCheck)
            
            // Set up event handler
            pluginEnabledCheck.addActionListener {
                configService.setPluginEnabled(pluginEnabledCheck.isSelected)
            }
            
            return panel
        }

        private fun createStatusPanel(): JPanel {
            val panel = JBPanel<JBPanel<*>>(GridBagLayout())
            val gbc = GridBagConstraints()
            
            // Status section
            gbc.gridx = 0; gbc.gridy = 0
            gbc.anchor = GridBagConstraints.WEST
            gbc.insets = Insets(5, 5, 5, 5)
            panel.add(JBLabel("FCS Binary Status:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(statusLabel, gbc)
            
            gbc.gridx = 2
            gbc.weightx = 0.0
            gbc.fill = GridBagConstraints.NONE
            panel.add(refreshButton, gbc)
            
            gbc.gridx = 3
            panel.add(downloadButton, gbc)
            
            // Version section
            gbc.gridx = 0; gbc.gridy = 1
            gbc.weightx = 0.0
            panel.add(JBLabel("Version:"), gbc)
            
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(versionLabel, gbc)
            
            // Download link section
            gbc.gridx = 0; gbc.gridy = 2
            gbc.gridwidth = 3
            gbc.weightx = 1.0
            panel.add(downloadLinkLabel, gbc)
            
            // Set up event handlers
            refreshButton.addActionListener { 
                outputArea.append("🔄 Refreshing FCS binary status...\n")
                updateStatus()
                val isAvailable = binaryService.isFCSAvailable()
                if (isAvailable) {
                    val version = binaryService.getFCSVersion() ?: "Unknown"
                    outputArea.append("✅ FCS CLI found - Version: $version\n")
                } else {
                    outputArea.append("❌ FCS CLI not found\n")
                }
                outputArea.append("\n")
            }
            
            downloadButton.addActionListener {
                downloadFCS()
            }
            
            return panel
        }
        
        private fun createConfigurationPanel(): JPanel {
            val panel = JBPanel<JBPanel<*>>(GridBagLayout())
            panel.setBorder(BorderFactory.createTitledBorder("Scan Configuration"))
            val gbc = GridBagConstraints()
            gbc.insets = Insets(5, 5, 5, 5)
            gbc.anchor = GridBagConstraints.WEST

            // --- Auto-scan on save ---
            val autoScanCheck = JCheckBox("Auto-scan on save", configService.getAutoScanOnSave())
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(autoScanCheck, gbc)

            // --- Scan Paths ---
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(JBLabel("Scan Paths:"), gbc)

            val scanPathsModel = DefaultListModel<String>()
            configService.getScanPaths().forEach { scanPathsModel.addElement(it) }
            val scanPathsList = com.intellij.ui.components.JBList(scanPathsModel)
            scanPathsList.visibleRowCount = 3
            val scanPathsScrollPane = JScrollPane(scanPathsList)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.gridheight = 2
            panel.add(scanPathsScrollPane, gbc)

            val pathButtonPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0))
            val addPathBtn = javax.swing.JButton("Add")
            val removePathBtn = javax.swing.JButton("Remove")
            pathButtonPanel.add(addPathBtn)
            pathButtonPanel.add(removePathBtn)
            gbc.gridx = 2; gbc.gridheight = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.VERTICAL
            panel.add(pathButtonPanel, gbc)

            addPathBtn.addActionListener {
                val input = JOptionPane.showInputDialog(panel, "Enter scan path:", "Add Scan Path", JOptionPane.PLAIN_MESSAGE)
                if (!input.isNullOrBlank()) {
                    scanPathsModel.addElement(input.trim())
                    configService.setScanPaths((0 until scanPathsModel.size).map { scanPathsModel.getElementAt(it) })
                }
            }
            removePathBtn.addActionListener {
                val idx = scanPathsList.selectedIndex
                if (idx >= 0) {
                    scanPathsModel.removeElementAt(idx)
                    configService.setScanPaths((0 until scanPathsModel.size).map { scanPathsModel.getElementAt(it) })
                }
            }

            // --- File Patterns ---
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridheight = 1; gbc.gridwidth = 1
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(JBLabel("File Patterns:"), gbc)

            val currentPatterns = configService.getFilePatterns().joinToString("\n")
            val patternsArea = javax.swing.JTextArea(currentPatterns, 4, 30)
            patternsArea.lineWrap = false
            val patternsScroll = JScrollPane(patternsArea)
            gbc.gridx = 1; gbc.gridwidth = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH
            panel.add(patternsScroll, gbc)

            val resetPatternsBtn = javax.swing.JButton("Reset")
            resetPatternsBtn.toolTipText = "Reset to default file patterns"
            gbc.gridx = 2; gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(resetPatternsBtn, gbc)

            patternsArea.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) {
                    val patterns = patternsArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (patterns.isNotEmpty()) configService.setFilePatterns(patterns)
                }
            })
            resetPatternsBtn.addActionListener {
                configService.setFilePatterns(emptyList())
                patternsArea.text = FCSConfigurationService.DEFAULT_FILE_PATTERNS.joinToString("\n")
            }

            // --- Platforms ---
            gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE; gbc.gridheight = 1
            panel.add(JBLabel("Platforms:"), gbc)

            val platformsModel = DefaultListModel<String>()
            configService.getPlatforms().forEach { platformsModel.addElement(it) }
            val platformsList = com.intellij.ui.components.JBList(platformsModel)
            platformsList.visibleRowCount = 3
            val platformsScrollPane = JScrollPane(platformsList)
            platformsScrollPane.toolTipText = "Leave empty to scan all platforms"
            gbc.gridx = 1; gbc.gridwidth = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH; gbc.gridheight = 2
            panel.add(platformsScrollPane, gbc)

            val platformButtonPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0))
            val addPlatformBtn = javax.swing.JButton("Add")
            val removePlatformBtn = javax.swing.JButton("Remove")
            platformButtonPanel.add(addPlatformBtn)
            platformButtonPanel.add(removePlatformBtn)
            gbc.gridx = 2; gbc.gridheight = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.VERTICAL
            panel.add(platformButtonPanel, gbc)

            addPlatformBtn.addActionListener {
                val current = (0 until platformsModel.size).map { platformsModel.getElementAt(it) }.toSet()
                val available = configService.validPlatforms.filter { it !in current }.toTypedArray()
                if (available.isEmpty()) return@addActionListener
                val choice = JOptionPane.showInputDialog(
                    panel, "Select platform:", "Add Platform",
                    JOptionPane.PLAIN_MESSAGE, null, available, available[0]
                ) as? String ?: return@addActionListener
                platformsModel.addElement(choice)
                configService.setPlatforms((0 until platformsModel.size).map { platformsModel.getElementAt(it) })
            }
            removePlatformBtn.addActionListener {
                val idx = platformsList.selectedIndex
                if (idx >= 0) {
                    platformsModel.removeElementAt(idx)
                    configService.setPlatforms((0 until platformsModel.size).map { platformsModel.getElementAt(it) })
                }
            }

            // --- Minimum Severity ---
            gbc.gridx = 0; gbc.gridy = 6; gbc.gridheight = 1; gbc.gridwidth = 1
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(JBLabel("Minimum Severity:"), gbc)

            val thresholdOptions = arrayOf(
                "All (Informational and above)",
                "Low and above",
                "Medium and above",
                "High and above",
                "Critical only"
            )
            val thresholdCombo = JComboBox(thresholdOptions)
            val currentThreshold = when {
                configService.isAllSeveritiesSelected() -> 0
                configService.getSelectedSeverities().contains(FCSConfigurationService.SeverityLevel.LOW) -> 1
                configService.getSelectedSeverities().contains(FCSConfigurationService.SeverityLevel.MEDIUM) -> 2
                configService.getSelectedSeverities().contains(FCSConfigurationService.SeverityLevel.HIGH) -> 3
                configService.getSelectedSeverities().contains(FCSConfigurationService.SeverityLevel.CRITICAL) -> 4
                else -> 0
            }
            thresholdCombo.selectedIndex = currentThreshold
            gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(thresholdCombo, gbc)

            // --- Exclude Secrets ---
            val excludeSecretsCheck = JCheckBox("Exclude Secrets", configService.getExcludeSecrets())
            gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 3; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(excludeSecretsCheck, gbc)

            // --- Scan Timeout ---
            gbc.gridx = 0; gbc.gridy = 8; gbc.gridwidth = 1
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(JBLabel("Scan Timeout (seconds):"), gbc)

            val timeoutSpinner = javax.swing.JSpinner(
                javax.swing.SpinnerNumberModel(configService.getScanTimeout(), 1, Int.MAX_VALUE, 30)
            )
            gbc.gridx = 1; gbc.gridwidth = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(timeoutSpinner, gbc)

            timeoutSpinner.addChangeListener {
                configService.setScanTimeout(timeoutSpinner.value as Int)
            }

            // --- Event handlers ---
            thresholdCombo.addActionListener {
                when (thresholdCombo.selectedIndex) {
                    0 -> configService.setSelectedSeverities(emptySet())
                    1 -> configService.setSelectedSeverities(setOf(FCSConfigurationService.SeverityLevel.LOW))
                    2 -> configService.setSelectedSeverities(setOf(FCSConfigurationService.SeverityLevel.MEDIUM))
                    3 -> configService.setSelectedSeverities(setOf(FCSConfigurationService.SeverityLevel.HIGH))
                    4 -> configService.setSelectedSeverities(setOf(FCSConfigurationService.SeverityLevel.CRITICAL))
                }
            }
            excludeSecretsCheck.addActionListener { configService.setExcludeSecrets(excludeSecretsCheck.isSelected) }
            autoScanCheck.addActionListener { configService.setAutoScanOnSave(autoScanCheck.isSelected) }

            return panel
        }
        
        private fun updateStatus() {
            val isAvailable = binaryService.isFCSAvailable()

            if (isAvailable) {
                statusLabel.text = "✅ Available"
                statusLabel.foreground = java.awt.Color.GREEN

                val rawVersion = binaryService.getFCSVersion() ?: "Unknown"
                val cleanVersion = if (rawVersion.startsWith("fcs version: ")) {
                    rawVersion.removePrefix("fcs version: ")
                } else {
                    rawVersion
                }

                val versionNote = when {
                    binaryService.isVersionAboveMaximum(cleanVersion) ->
                        " ⚠️ Above maximum validated version (${FCSBinaryService.MAXIMUM_CLI_VERSION}). Update the plugin."
                    !binaryService.isVersionCompatible(cleanVersion) ->
                        " ⚠️ Below minimum required version (${FCSBinaryService.MINIMUM_CLI_VERSION})."
                    cleanVersion == FCSBinaryService.MAXIMUM_CLI_VERSION ->
                        " ✅ Latest compatible version"
                    else ->
                        " (latest compatible: v${FCSBinaryService.MAXIMUM_CLI_VERSION})"
                }
                versionLabel.text = cleanVersion + versionNote

                downloadButton.text = "Update FCS CLI"
                downloadButton.isEnabled = true
            } else {
                statusLabel.text = "❌ Not Found"
                statusLabel.foreground = java.awt.Color.RED

                versionLabel.text = "N/A"

                downloadButton.text = "Download FCS CLI"
                downloadButton.isEnabled = true
            }
        }
        
        private fun promptForCredentials(): Map<String, String>? {
            val clientIdField = JTextField(30)
            val clientSecretField = JPasswordField(30)
            val cloudField = JTextField("us-1", 10)
            val versionField = JTextField(10)
            val proxyUrlField = JTextField(30)

            val panel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints()
            gbc.insets = Insets(5, 5, 5, 5)
            gbc.anchor = GridBagConstraints.WEST

            // Add warning label
            gbc.gridx = 0; gbc.gridy = 0
            gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.HORIZONTAL
            panel.add(JLabel("<html><b>⚠️ Credentials will NOT be stored - enter for this download only</b></html>"), gbc)

            gbc.gridwidth = 1
            gbc.gridx = 0; gbc.gridy = 1
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Client ID:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(clientIdField, gbc)

            gbc.gridx = 0; gbc.gridy = 2
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Client Secret:"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(clientSecretField, gbc)

            gbc.gridx = 0; gbc.gridy = 3
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Cloud Region (default: us-1):"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(cloudField, gbc)

            gbc.gridx = 0; gbc.gridy = 4
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Version (optional):"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            versionField.toolTipText = "Leave blank for latest compatible (v${FCSBinaryService.MAXIMUM_CLI_VERSION})"
            panel.add(versionField, gbc)

            gbc.gridx = 0; gbc.gridy = 5
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            panel.add(JLabel("Proxy URL (optional):"), gbc)
            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            panel.add(proxyUrlField, gbc)

            // Add environment variable instructions
            gbc.gridx = 0; gbc.gridy = 6
            gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.HORIZONTAL
            val instructions = "<html><i>Tip: To avoid this dialog, set environment variables:<br/>" +
                "export FALCON_CLIENT_ID=\"\"<br/>" +
                "export FALCON_CLIENT_SECRET=\"\"<br/>" +
                "export FALCON_CLOUD=\"\"<br/>" +
                "export FCS_VERSION=\"\"<br/>" +
                "export HTTPS_PROXY=\"\" (for proxy URL)<br/>" +
                "Then restart IntelliJ IDEA from that terminal</i></html>"
            panel.add(JLabel(instructions), gbc)

            val result = JOptionPane.showConfirmDialog(
                content,
                panel,
                "Enter CrowdStrike API Credentials",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )

            return if (result == JOptionPane.OK_OPTION) {
                val clientId = clientIdField.text?.trim() ?: ""
                val clientSecret = String(clientSecretField.password).trim()

                if (clientId.isEmpty() || clientSecret.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        content,
                        "Both Client ID and Client Secret are required!",
                        "Missing Credentials",
                        JOptionPane.ERROR_MESSAGE
                    )
                    null
                } else {
                    mapOf(
                        "clientId" to clientId,
                        "clientSecret" to clientSecret,
                        "cloud" to (cloudField.text?.trim()?.ifEmpty { "us-1" } ?: "us-1"),
                        "version" to (versionField.text?.trim() ?: ""),
                        "proxyUrl" to (proxyUrlField.text?.trim() ?: "")
                    )
                }
            } else {
                null
            }
        }
        
        private fun downloadFCS() {
            outputArea.text = "Starting FCS CLI download...\n"
            downloadButton.isEnabled = false
            
            // Check for environment variables first
            var clientId = System.getenv("FALCON_CLIENT_ID")
            var clientSecret = System.getenv("FALCON_CLIENT_SECRET")
            var cloud = System.getenv("FALCON_CLOUD") ?: "us-1"
            var version = System.getenv("FCS_VERSION") ?: ""
            var proxyUrl = System.getenv("HTTPS_PROXY")?.takeIf { it.isNotBlank() } ?: ""
            
            // If environment variables are not available, prompt user
            if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                outputArea.append("Environment variables not found. Opening credential dialog...\n")
                
                val credentials = promptForCredentials()
                if (credentials == null) {
                    outputArea.append("❌ Download cancelled - credentials required\n")
                    downloadButton.isEnabled = true
                    return
                }
                
                clientId = credentials["clientId"]!!
                clientSecret = credentials["clientSecret"]!!
                
                // Use dialog values for optional parameters if env vars not set
                cloud = credentials["cloud"]!!
                version = credentials["version"]!!
                proxyUrl = credentials["proxyUrl"]!!
                
                outputArea.append("Using provided credentials for this download session only.\n")
            } else {
                outputArea.append("Using environment variables for credentials.\n")
            }
            
            outputArea.append("Environment configuration:\n")
            outputArea.append("  Client ID: ${clientId.take(8)}***\n")
            outputArea.append("  Client Secret: ***\n")
            outputArea.append("  Cloud: $cloud\n")
            outputArea.append("  Version: ${if (version.isBlank()) "latest" else version}\n")
            outputArea.append("  Proxy URL: ${if (proxyUrl.isBlank()) "none" else proxyUrl}\n")
            outputArea.append("\n")
            
            // Run download in background thread
            Thread {
                try {
                    val scriptStream = javaClass.getResourceAsStream("/scripts/fcs_download.sh")
                    if (scriptStream == null) {
                        SwingUtilities.invokeLater {
                            outputArea.append("❌ Download failed: bundled fcs_download.sh not found in plugin resources. Try reinstalling the FCS plugin.\n")
                            downloadButton.isEnabled = true
                        }
                        return@Thread
                    }
                    val tempScript = java.nio.file.Files.createTempFile("fcs_download_", ".sh")
                    try {
                        scriptStream.use { input ->
                            java.nio.file.Files.copy(input, tempScript, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                        tempScript.toFile().setExecutable(true, true)

                        SwingUtilities.invokeLater {
                            outputArea.append("Executing download script from plugin resources\n\n")
                        }

                        val command = listOf("bash", tempScript.toString())
                        val processBuilder = ProcessBuilder(command)

                        // Set environment variables for the script
                        processBuilder.environment().apply {
                            put("FALCON_CLIENT_ID", clientId)
                            put("FALCON_CLIENT_SECRET", clientSecret)
                            put("FALCON_CLOUD", cloud)
                            if (version.isNotBlank()) {
                                put("FCS_VERSION", version)
                            }
                            // Always pass the max compatible version so the script never downloads
                            // a version the plugin hasn't been validated against
                            put("FCS_MAX_VERSION", FCSBinaryService.MAXIMUM_CLI_VERSION)
                            if (proxyUrl.isNotBlank()) {
                                put("HTTPS_PROXY", proxyUrl)
                            }
                        }

                        processBuilder.redirectErrorStream(true)
                        val process = processBuilder.start()

                        // Read output in real-time
                        val reader = process.inputStream.bufferedReader()
                        reader.useLines { lines ->
                            lines.forEach { line ->
                                SwingUtilities.invokeLater {
                                    outputArea.append("$line\n")
                                    outputArea.caretPosition = outputArea.document.length
                                }
                            }
                        }

                        val exitCode = process.waitFor()

                        SwingUtilities.invokeLater {
                            outputArea.append("\n--- Download completed with exit code: $exitCode ---\n")
                            if (exitCode == 0) {
                                outputArea.append("✅ FCS CLI download completed successfully!\n")

                                // Refresh binary service cache and update status
                                binaryService.refreshBinaryPath()
                                updateStatus()

                                // Run config migration automatically after a successful download
                                val migrated = binaryService.runMigrateConfig()
                                val migrateMsg = if (migrated)
                                    "✅ Config migrated automatically (fcs migrate-config)."
                                else
                                    "⚠️ Config migration may be needed — run 'fcs migrate-config' in your terminal if scans aren't working."
                                outputArea.append("$migrateMsg\n")

                                // Store the newly downloaded version
                                val newVersion = binaryService.getFCSVersion()
                                if (newVersion != null) {
                                    configService.state.lastKnownCliVersion = newVersion
                                }
                            } else {
                                outputArea.append("❌ Download failed (exit code $exitCode). Check the output above. Common causes: invalid credentials, network/proxy issues, or unsupported platform. Verify FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, and FALCON_CLOUD are correct.\n")
                            }

                            downloadButton.isEnabled = true
                        }
                    } finally {
                        try {
                            java.nio.file.Files.deleteIfExists(tempScript)
                        } catch (e: Exception) {
                            // deletion failure is non-fatal
                        }
                    }
                    
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        outputArea.append("\n❌ Download script execution failed: ${e.message}. This may be a permissions issue — check that /tmp is writable, or try running 'fcs' from the terminal directly.\n")
                        downloadButton.isEnabled = true
                    }
                }
            }.start()
        }
        
    }
}
