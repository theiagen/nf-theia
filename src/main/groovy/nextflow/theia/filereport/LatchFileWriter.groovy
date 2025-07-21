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

package nextflow.theia.filereport

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Handles writing files to Latch storage using Nextflow's built-in capabilities.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class LatchFileWriter {

    /**
     * Write content to a Latch path using Nextflow's file system.
     * This leverages Nextflow's file() method which handles latch:// paths through the custom file system provider.
     */
    static void writeToLatch(String latchPath, String content) {
        try {
            // Use Nextflow's file() method which handles latch:// paths natively via the file system provider
            final latchFile = nextflow.file.FileHelper.asPath(latchPath)
            
            // Nextflow's file system provider handles latch operations
            java.nio.file.Files.write(latchFile, content.getBytes('UTF-8'))
            
            log.debug "Successfully wrote file to Latch: ${latchPath}"
        } catch (Exception e) {
            log.warn "Failed to write file to Latch: ${latchPath}", e
            throw e
        }
    }

    /**
     * Create Latch directories if needed (Latch handles implicit directory creation).
     * This is a no-op for Latch but included for consistency with local file operations.
     */
    static void createLatchDirectories(String latchDirectoryPath) {
        // Latch doesn't require explicit directory creation
        // Directories are created implicitly when objects are uploaded
        log.trace "Latch directories are created implicitly: ${latchDirectoryPath}"
    }
}