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

import java.nio.file.Path
import java.nio.file.Paths

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.script.params.FileOutParam
import nextflow.script.params.TupleOutParam
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import nextflow.theia.filereport.CloudFileUtils

/**
 * Reports process output file paths to JSON files.
 * 
 * Creates JSON files named after process name + tag (if available) and saves them 
 * to publishDir locations. The JSON contains the list of output file paths.
 * 
 * Supports collation mode to combine all reports into a single file.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class FileReportObserver implements TraceObserver {

    private Session session
    private FileReportConfig config
    private FileReportCollector collector

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        this.config = FileReportConfig.fromSession(session)
        this.collector = new FileReportCollector()
        log.info "FileReportObserver initialized - file reporting enabled (collate: ${config.collate})"
    }

    /**
     * When a task is completed, collect its output files and write a JSON report.
     */
    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        final task = handler.task
        
        log.info "Processing task completion: ${task.name}"
        
        if( !task.isSuccess() ) {
            log.trace "Task ${task.name} failed, skipping file report"
            return
        }

        try {
            generateFileReport(task, trace)
        } catch( Exception e ) {
            log.warn "Error generating file report for task ${task.name}", e
        }
    }

    /**
     * Track file publishing to map source paths to published destination paths.
     */
    @Override
    void onFilePublish(Path destination, Path source) {
        log.debug "File published: ${source} -> ${destination}"
        collector.recordFilePublish(destination, source)
    }

    /**
     * Generate a JSON report of output files for a completed task.
     */
    private void generateFileReport(TaskRun task, TraceRecord trace) {
        // Record publishDir paths for later use in collated reports
        collector.recordTaskPublishDirs(task)
        
        final jsonContent = collector.createTaskReport(task, trace)
        
        if (!jsonContent) {
            return
        }

        // Generate JSON file name
        final processName = task.processor.name
        final tag = trace.get('tag') as String
        final jsonFileName = tag ? "${processName}_${tag}.json" : "${processName}.json"

        // Get output file paths for checking publication status from the JSON content
        final Map outputsMap = (Map)jsonContent.outputs
        final List<Path> workDirOutputFiles = []
        
        // Extract all work directory files from all emit groups and log emit names
        log.debug "=== EMIT NAMES EXTRACTION DEBUG ==="
        log.debug "Task: ${task.name}"
        log.debug "Process: ${processName}"
        log.debug "Tag: ${tag}"
        log.debug "Total emit groups: ${outputsMap.size()}"
        
        outputsMap.each { emitName, emitData ->
            final Map emitMap = (Map)emitData
            final List<String> workDirPaths = emitMap.workDirFiles as List<String>
            
            log.debug "Emit group '${emitName}':"
            log.debug "  - Work directory files: ${workDirPaths}"
            log.debug "  - File count: ${workDirPaths.size()}"
            
            workDirPaths.each { pathStr ->
                workDirOutputFiles << Paths.get(pathStr)
            }
        }
        log.debug "=== END EMIT NAMES DEBUG ==="

        boolean hasPublishedPaths = workDirOutputFiles.any { collector.isFilePublished(it) }
        boolean taskHasPublishDir = hasPublishDir(task)

        log.debug "Generating file report for process ${processName} with tag '${tag}': ${jsonFileName}"
        log.debug "  hasPublishedPaths: ${hasPublishedPaths}, taskHasPublishDir: ${taskHasPublishDir}"
        log.debug "  workDirOutputFiles: ${workDirOutputFiles}"

        // Always write individual JSON files immediately
        log.debug "Writing individual JSON file for ${task.name}: ${jsonFileName}"
        JsonFileWriter.writeToPublishDirs(task, jsonFileName, jsonContent)
        
        // Record this individual JSON file for later update with published files
        collector.recordIndividualJsonFile(task, jsonFileName)
    }
    
    /**
     * Check if the task has any publishDir configuration.
     */
    private boolean hasPublishDir(TaskRun task) {
        final publishers = task.config.getPublishDir()
        return !publishers.isEmpty() && publishers.any { it.enabled }
    }

    
    /**
     * Handle any remaining tasks when the workflow completes.
     */
    @Override
    void onFlowComplete() {
        try {
            // Update individual JSON files with published file information
            log.debug "Updating individual JSON files with published file information"
            collector.updateIndividualJsonFiles()
            
            // Write collated report if enabled
            if (config.collate) {
                writeCollatedReport()
            }
        } catch (Exception e) {
            log.error "Error in onFlowComplete", e
        }
    }

    /**
     * Write a collated report containing all task data.
     */
    private void writeCollatedReport() {
        try {
            final collatedData = collector.createCollatedReport()
            
            // Write to publishDir locations only
            writeCollatedToPublishDirs(collatedData)
            
            final taskCount = ((List)collatedData.tasks).size()
            log.info "Collated file report written with ${taskCount} tasks"
        } catch (Exception e) {
            log.warn "Failed to write collated file report", e
        }
    }
    
    /**
     * Write collated report to publishDir folders.
     * Writes to the common base publishDir only, not to individual sample subdirectories.
     */
    private void writeCollatedToPublishDirs(Map collatedData) {
        final taskList = (List) collatedData.tasks
        
        // Get all publishDir paths and find their common base directories
        final allPublishDirs = [] as Set<String>
        collector.getPublishDirPaths().each { publishPath ->
            allPublishDirs.add(publishPath.toString())
        }
        
        // If no publish directories found, log the situation
        if (allPublishDirs.isEmpty()) {
            log.debug "No publishDir locations found for collated report"
            return
        }
        
        // Find common base directories to avoid writing to every subdirectory
        final basePublishDirs = findBasePublishDirs(allPublishDirs)
        
        // Write to each base publishDir
        basePublishDirs.each { publishPathStr ->
            try {
                final Path publishPathObj = Paths.get(publishPathStr)
                final collatedFile = publishPathObj.resolve(config.collatedFileName)
                JsonFileWriter.writeToFile(collatedFile, collatedData)
                log.info "Written collated report to: ${collatedFile}"
            } catch (Exception e) {
                log.debug "Failed to write collated report to publishDir ${publishPathStr}", e
            }
        }
    }
    
    /**
     * Find common base directories from all publishDir paths to avoid duplicating
     * collated files in every subdirectory.
     */
    private Set<String> findBasePublishDirs(Set<String> allPublishDirs) {
        if (allPublishDirs.size() <= 1) {
            return allPublishDirs
        }
        
        final basePublishDirs = [] as Set<String>
        final pathList = new ArrayList(allPublishDirs)
        
        // Find the longest common prefix among all paths
        String commonPrefix = findLongestCommonPrefix(pathList)
        
        if (commonPrefix && !commonPrefix.equals("/") && !commonPrefix.equals("")) {
            // Make sure the common prefix ends at a directory boundary
            final lastSlash = commonPrefix.lastIndexOf('/')
            if (lastSlash > 0) {
                commonPrefix = commonPrefix.substring(0, lastSlash)
            }
            
            // Verify this is a meaningful base directory (not just filesystem root)
            if (commonPrefix.length() > 1 && !commonPrefix.equals("/Users")) {
                basePublishDirs.add(commonPrefix)
                log.debug "Found common base publishDir: ${commonPrefix}"
                return basePublishDirs
            }
        }
        
        // If no meaningful common prefix found, fall back to all unique paths
        log.debug "No meaningful common base found, using all publishDirs: ${allPublishDirs}"
        return allPublishDirs
    }
    
    /**
     * Find the longest common prefix among a list of paths.
     */
    private String findLongestCommonPrefix(List<String> paths) {
        if (paths.isEmpty()) {
            return ""
        }
        
        if (paths.size() == 1) {
            // For a single path, return its parent directory
            final path = Paths.get(paths[0])
            return path.parent?.toString() ?: paths[0]
        }
        
        // Sort paths to make comparison easier
        paths.sort()
        
        final first = paths[0]
        final last = paths[paths.size() - 1]
        
        final minLength = Math.min(first.length(), last.length())
        int commonLength = 0
        
        for (int i = 0; i < minLength; i++) {
            if (first.charAt(i) == last.charAt(i)) {
                commonLength++
            } else {
                break
            }
        }
        
        return first.substring(0, commonLength)
    }
}