package com.crowdstrike.fcscliplugin

import com.crowdstrike.fcscliplugin.annotators.FCSExternalAnnotator
import com.crowdstrike.fcscliplugin.inspections.FCSInspection
import com.crowdstrike.fcscliplugin.services.FCSBinaryService
import com.crowdstrike.fcscliplugin.services.FCSConfigurationService
import com.crowdstrike.fcscliplugin.services.FCSFileScanTriggerService
import com.crowdstrike.fcscliplugin.services.FCSResultsService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Smoke tests — verify the plugin wires up correctly without throwing.
 *
 * These catch registration errors, broken module graphs, and misconfigured
 * extension points before any functional tests run. Failures here indicate
 * a structural problem in plugin.xml or service registration, not a logic bug.
 */
class FCSPluginSmokeTest : BasePlatformTestCase() {

    // -------------------------------------------------------------------------
    // Service instantiation
    // -------------------------------------------------------------------------

    fun `test FCSConfigurationService can be retrieved from project`() {
        val service = project.getService(FCSConfigurationService::class.java)
        assertNotNull("FCSConfigurationService must be registered as a project service", service)
    }

    fun `test FCSBinaryService can be retrieved from project`() {
        val service = project.getService(FCSBinaryService::class.java)
        assertNotNull("FCSBinaryService must be registered as a project service", service)
    }

    fun `test FCSResultsService can be retrieved from project`() {
        val service = project.getService(FCSResultsService::class.java)
        assertNotNull("FCSResultsService must be registered as a project service", service)
    }

    fun `test FCSFileScanTriggerService can be retrieved from project`() {
        val service = project.getService(FCSFileScanTriggerService::class.java)
        assertNotNull("FCSFileScanTriggerService must be registered as a project service", service)
    }

    // -------------------------------------------------------------------------
    // Extension point classes exist and instantiate
    // -------------------------------------------------------------------------

    fun `test FCSExternalAnnotator can be instantiated`() {
        assertNotNull(FCSExternalAnnotator())
    }

    fun `test FCSInspection can be instantiated`() {
        assertNotNull(FCSInspection())
    }

    fun `test FCSToolWindowFactory can be instantiated`() {
        assertNotNull(FCSToolWindowFactory())
    }

    // -------------------------------------------------------------------------
    // Default configuration state is valid
    // -------------------------------------------------------------------------

    fun `test default configuration produces a non-empty target path`() {
        val config = project.getService(FCSConfigurationService::class.java)
        assertTrue(config.getTargetPath().isNotEmpty())
    }

    fun `test default configuration all severities selected`() {
        val config = project.getService(FCSConfigurationService::class.java)
        assertTrue(config.isAllSeveritiesSelected())
    }

    fun `test FCSFileScanTriggerService initialize does not throw`() {
        val service = project.getService(FCSFileScanTriggerService::class.java)
        // initialize registers a VirtualFileListener — must not throw in test environment
        service.initialize()
    }
}
