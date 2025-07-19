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

import java.nio.file.Files
import java.nio.file.Path

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.processor.PublishDir
import nextflow.processor.TaskRun

/**
 * Handles writing JSON files to publish directories.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class JsonFileWriter {
    
    /**
     * Write JSON content to all publishDir locations for the task.
     */
    static void writeToPublishDirs(TaskRun task, String jsonFileName, Map jsonContent) {
        final publishers = task.config.getPublishDir()
        
        if (publishers.isEmpty()) {
            log.trace "No publishDir configured for task ${task.name}, skipping JSON report"
            return
        }

        final jsonText = JsonOutput.prettyPrint(JsonOutput.toJson(jsonContent))

        for (PublishDir publisher : publishers) {
            if (!publisher.enabled) {
                continue
            }

            try {
                final publishPath = publisher.path
                final jsonFile = publishPath.resolve(jsonFileName)
                
                if (CloudFileUtils.isS3Path(publishPath)) {
                    // Handle S3 paths
                    final s3JsonPath = publishPath.toString() + "/" + jsonFileName
                    S3FileWriter.writeToS3(s3JsonPath, jsonText)
                    log.debug "Written file report to S3: ${s3JsonPath}"
                } else {
                    // Handle local filesystem paths
                    Files.createDirectories(publishPath)
                    Files.write(jsonFile, jsonText.getBytes('UTF-8'))
                    log.debug "Written file report to: ${jsonFile}"
                }
            } catch (Exception e) {
                log.debug "Failed to write file report to ${publisher.path}/${jsonFileName}", e
            }
        }
    }
    
    /**
     * Write JSON content to a specific file path.
     */
    static void writeToFile(Path filePath, Map jsonContent) {
        try {
            final jsonText = JsonOutput.prettyPrint(JsonOutput.toJson(jsonContent))
            
            if (CloudFileUtils.isS3Path(filePath)) {
                // Handle S3 paths
                S3FileWriter.writeToS3(filePath.toString(), jsonText)
                log.info "Written collated file report to S3: ${filePath}"
            } else {
                // Handle local filesystem paths
                Files.createDirectories(filePath.parent)
                Files.write(filePath, jsonText.getBytes('UTF-8'))
                log.info "Written collated file report to: ${filePath}"
            }
        } catch (Exception e) {
            log.warn "Failed to write collated file report to ${filePath}", e
        }
    }
}