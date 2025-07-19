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
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord

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
        log.info "File published: ${source} -> ${destination}"
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

        // Get output file paths for checking publication status
        final outputParams = task.getOutputsByType(FileOutParam)
        final workDirOutputFiles = outputParams.values().flatten().collect { file ->
            file instanceof Path ? file : Paths.get(file.toString())
        }

        boolean hasPublishedPaths = workDirOutputFiles.any { collector.isFilePublished(it) }
        boolean taskHasPublishDir = hasPublishDir(task)

        log.info "Generating file report for process ${processName} with tag '${tag}': ${jsonFileName}"
        log.info "  hasPublishedPaths: ${hasPublishedPaths}, taskHasPublishDir: ${taskHasPublishDir}"
        log.info "  workDirOutputFiles: ${workDirOutputFiles}"

        // Always write individual JSON files immediately
        log.info "Writing individual JSON file for ${task.name}: ${jsonFileName}"
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
            log.info "Updating individual JSON files with published file information"
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
            
            // Write to work directory
            final workDirOutputFile = session.workDir.resolve(config.collatedFileName)
            JsonFileWriter.writeToFile(workDirOutputFile, collatedData)
            
            // Write to publishDir locations
            writeCollatedToPublishDirs(collatedData)
            
            final taskCount = ((List)collatedData.tasks).size()
            log.info "Collated file report written with ${taskCount} tasks to: ${workDirOutputFile}"
        } catch (Exception e) {
            log.warn "Failed to write collated file report", e
        }
    }
    
    /**
     * Write collated report to root publishDir folders (parent directories of process-specific publishDirs).
     */
    private void writeCollatedToPublishDirs(Map collatedData) {
        final taskList = (List) collatedData.tasks
        final processPublishDirs = [] as Set
        
        // Get publishDirs from collector's recorded paths
        processPublishDirs.addAll(collector.getPublishDirPaths())
        
        // Extract root publishDir folders (parent directories)
        final rootPublishDirs = [] as Set
        processPublishDirs.each { publishPath ->
            try {
                final publishPathObj = publishPath instanceof Path ? publishPath : Paths.get(publishPath.toString())
                final parentDir = publishPathObj.parent
                if (parentDir != null) {
                    rootPublishDirs << parentDir
                } else {
                    // If no parent, use the path itself (already a root dir)
                    rootPublishDirs << publishPathObj
                }
            } catch (Exception e) {
                log.debug "Failed to extract parent directory from ${publishPath}", e
                // Fallback to using the original path
                final publishPathObj = publishPath instanceof Path ? publishPath : Paths.get(publishPath.toString())
                rootPublishDirs << publishPathObj
            }
        }
        
        // If still no publish directories found, log the situation
        if (rootPublishDirs.isEmpty()) {
            log.debug "No root publishDir locations found for collated report"
            return
        }
        
        // Write to each root publishDir
        rootPublishDirs.each { rootPath ->
            try {
                final Path rootPathObj = rootPath instanceof Path ? rootPath : Paths.get(rootPath.toString())
                final collatedFile = rootPathObj.resolve(config.collatedFileName)
                JsonFileWriter.writeToFile(collatedFile, collatedData)
                log.debug "Written collated report to root publishDir: ${collatedFile}"
            } catch (Exception e) {
                log.debug "Failed to write collated report to root publishDir ${rootPath}", e
            }
        }
    }
}