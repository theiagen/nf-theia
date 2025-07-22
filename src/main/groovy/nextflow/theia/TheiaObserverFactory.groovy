/*
 * Copyright 2025, Thanh Le Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.theia

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.theia.filereport.FileReportConfig
import nextflow.theia.filereport.FileReportObserver
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

/**
 * Observer factory for nf-theia plugin
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class TheiaObserverFactory implements TraceObserverFactory {

    @Override
    Collection<TraceObserver> create(Session session) {
        final config = session.config
        final result = new ArrayList<TraceObserver>()

        // Print plugin version
        final pluginVersion = getPluginVersion()
        log.info "nf-theia plugin version: ${pluginVersion}"

        // Check if file report is enabled
        if (FileReportConfig.isEnabled(config)) {
            log.info "Creating FileReportObserver"
            result << new FileReportObserver()
        }

        return result
    }

    /**
     * Get the plugin version from the MANIFEST.MF file
     */
    private String getPluginVersion() {
        try {
            // Try to read from plugin-specific MANIFEST.MF
            final urls = this.class.classLoader.getResources("META-INF/MANIFEST.MF")
            while (urls.hasMoreElements()) {
                final manifestUrl = urls.nextElement()
                try {
                    final manifest = new java.util.jar.Manifest(manifestUrl.openStream())
                    final attributes = manifest.mainAttributes
                    final pluginId = attributes.getValue("Plugin-Id")
                    if (pluginId == "nf-theia") {
                        final version = attributes.getValue("Plugin-Version")
                        if (version) {
                            return version
                        }
                    }
                } catch (Exception ignored) {
                    // Continue to next MANIFEST.MF
                }
            }
            
            // Fallback to package information
            final packageInfo = this.class.package
            final version = packageInfo?.implementationVersion ?: packageInfo?.specificationVersion
            return version ?: "unknown"
        } catch (Exception e) {
            log.debug "Failed to read plugin version: ${e.message}"
            return "unknown"
        }
    }
}