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
 * Handles writing files to AWS S3 using Nextflow's built-in capabilities.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class S3FileWriter {

    /**
     * Write content to an S3 path using Nextflow's file system.
     * This leverages Nextflow's built-in AWS S3 support which handles credentials and configuration.
     */
    static void writeToS3(String s3Path, String content) {
        try {
            // Use Nextflow's file() method which handles S3 paths natively
            final s3File = nextflow.file.FileHelper.asPath(s3Path)
            
            // Nextflow's file system provider handles S3 operations
            java.nio.file.Files.write(s3File, content.getBytes('UTF-8'))
            
            log.debug "Successfully wrote file to S3: ${s3Path}"
        } catch (Exception e) {
            log.warn "Failed to write file to S3: ${s3Path}", e
            throw e
        }
    }

    /**
     * Create S3 directories if needed (S3 doesn't require explicit directory creation).
     * This is a no-op for S3 but included for consistency with local file operations.
     */
    static void createS3Directories(String s3DirectoryPath) {
        // S3 doesn't require explicit directory creation
        // Directories are created implicitly when objects are uploaded
        log.trace "S3 directories are created implicitly: ${s3DirectoryPath}"
    }
}