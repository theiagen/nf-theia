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
    
    // Track pending tasks waiting for file publishing to complete
    private Map<TaskRun, Map> pendingReports = [:]

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
        collector.recordFilePublish(destination, source)
        
        // Check if any pending reports are waiting for this file
        synchronized(pendingReports) {
            pendingReports.each { task, reportData ->
                final workDirOutputFiles = reportData.workDirOutputFiles as List<Path>
                if( workDirOutputFiles.contains(source) ) {
                    log.debug "Updating pending report for task ${task.name} with published path: ${destination}"
                    finalizePendingReport(task, reportData)
                }
            }
        }
    }

    /**
     * Generate a JSON report of output files for a completed task.
     */
    private void generateFileReport(TaskRun task, TraceRecord trace) {
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

        log.debug "Generating file report for process ${processName} with tag '${tag}': ${jsonFileName} (hasPublishedPaths: ${hasPublishedPaths})"

        if (hasPublishedPaths || !hasPublishDir(task)) {
            // Always write individual files when fileReport is enabled
            JsonFileWriter.writeToPublishDirs(task, jsonFileName, jsonContent)
        } else {
            synchronized(pendingReports) {
                final pendingReport = new HashMap(jsonContent)
                pendingReport.jsonFileName = jsonFileName
                pendingReport.workDirOutputFiles = workDirOutputFiles
                pendingReports[task] = pendingReport
            }
            log.debug "Storing pending report for task ${task.name}, waiting for file publishing"
        }
    }
    
    /**
     * Check if the task has any publishDir configuration.
     */
    private boolean hasPublishDir(TaskRun task) {
        final publishers = task.config.getPublishDir()
        return !publishers.isEmpty() && publishers.any { it.enabled }
    }
    
    /**
     * Finalize a pending report when all files have been published.
     */
    private void finalizePendingReport(TaskRun task, Map reportData) {
        final workDirOutputFiles = reportData.workDirOutputFiles as List<Path>
        boolean allFilesPublished = workDirOutputFiles.every { collector.isFilePublished(it) }

        if (allFilesPublished) {
            // Update the report with published paths for each emit
            final outputsMap = (Map)reportData.outputs
            outputsMap.each { emitName, emitData ->
                final emitMap = (Map)emitData
                final workDirFiles = emitMap.workDirFiles as List<String>
                final publishedFiles = emitMap.publishedFiles as List<String>
                
                // Clear and repopulate published files for this emit
                publishedFiles.clear()
                workDirFiles.each { workFileStr ->
                    final workFile = Paths.get(workFileStr)
                    publishedFiles.addAll(
                        collector.getPublishedPaths(workFile).collect{ it.toString() }
                    )
                }
            }

            // Always write individual files when fileReport is enabled
            JsonFileWriter.writeToPublishDirs(task, reportData.jsonFileName as String, reportData)
            synchronized(pendingReports) {
                pendingReports.remove(task)
            }
            log.debug "Finalized pending report for task ${task.name}"
        }
    }

    
    /**
     * Handle any remaining pending reports when the workflow completes.
     */
    @Override
    void onFlowComplete() {
        try {
            // Clear any remaining pending reports since we always write individual files now
            synchronized(pendingReports) {
                pendingReports.clear()
            }

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
            
            // Write to results directory
            try {
                final resultsDir = session.baseDir.resolve('results')
                final resultsFile = resultsDir.resolve(config.collatedFileName)
                JsonFileWriter.writeToFile(resultsFile, collatedData)
            } catch (Exception e) {
                log.debug "Could not write to results directory", e
            }
            
            final taskCount = ((List)collatedData.tasks).size()
            log.info "Collated file report written with ${taskCount} tasks to: ${workDirOutputFile}"
        } catch (Exception e) {
            log.warn "Failed to write collated file report", e
        }
    }
    
    /**
     * Write collated report to all available publishDir locations from tasks.
     */
    private void writeCollatedToPublishDirs(Map collatedData) {
        final taskList = (List) collatedData.tasks
        final publishDirs = [] as Set
        
        // Collect all unique publishDir paths from tasks
        synchronized(pendingReports) {
            pendingReports.keySet().each { task ->
                task.config.getPublishDir().each { publisher ->
                    if (publisher.enabled) {
                        publishDirs << publisher.path
                    }
                }
            }
        }
        
        // If no pending reports, try to get publishDirs from session config
        if (publishDirs.isEmpty()) {
            try {
                final basePublishDir = session.baseDir.resolve('results')
                publishDirs << basePublishDir
            } catch (Exception e) {
                log.debug "Could not determine publishDir locations for collated report"
            }
        }
        
        // Write to each publishDir
        publishDirs.each { publishPath ->
            try {
                final publishPathObj = publishPath instanceof Path ? publishPath : Paths.get(publishPath.toString())
                final collatedFile = publishPathObj.resolve(config.collatedFileName)
                JsonFileWriter.writeToFile(collatedFile, collatedData)
                log.debug "Written collated report to: ${collatedFile}"
            } catch (Exception e) {
                log.debug "Failed to write collated report to ${publishPath}", e
            }
        }
    }
}