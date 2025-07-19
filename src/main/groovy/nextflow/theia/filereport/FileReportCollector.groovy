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
import nextflow.processor.TaskRun
import nextflow.script.params.FileOutParam
import nextflow.trace.TraceRecord

/**
 * Collects and manages file report data for tasks.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class FileReportCollector {
    
    // Track mapping from source (work dir) paths to published destination paths
    private final Map<Path, Set<Path>> publishedPathMap = [:].asSynchronized()
    
    // Store all task reports for collation
    private final List<Map> allTaskReports = [].asSynchronized()
    
    // Track unique publishDir paths encountered
    private final Set<Path> allPublishDirs = ([] as Set<Path>).asSynchronized()
    
    // Track individual JSON files that need to be updated with published files
    private final Map<TaskRun, String> taskJsonFiles = [:].asSynchronized()
    
    /**
     * Record a file publishing event.
     */
    void recordFilePublish(Path destination, Path source) {
        log.info "FileReportCollector: Recording file publish: ${source} -> ${destination}"
        
        synchronized(publishedPathMap) {
            if (!publishedPathMap.containsKey(source)) {
                publishedPathMap[source] = [] as Set
            }
            publishedPathMap[source] << destination
            log.info "FileReportCollector: publishedPathMap now contains ${publishedPathMap.size()} source files"
            log.info "FileReportCollector: Source ${source} maps to ${publishedPathMap[source]}"
        }
    }
    
    /**
     * Record publishDir paths from a task for later use.
     */
    void recordTaskPublishDirs(TaskRun task) {
        synchronized(allPublishDirs) {
            task.config.getPublishDir().each { publisher ->
                if (publisher.enabled) {
                    allPublishDirs << publisher.path
                }
            }
        }
    }
    
    /**
     * Get all recorded publishDir paths.
     */
    Set<Path> getPublishDirPaths() {
        synchronized(allPublishDirs) {
            return new HashSet(allPublishDirs)
        }
    }
    
    /**
     * Record that an individual JSON file was written for a task.
     */
    void recordIndividualJsonFile(TaskRun task, String jsonFileName) {
        synchronized(taskJsonFiles) {
            taskJsonFiles[task] = jsonFileName
            log.info "FileReportCollector: Recorded individual JSON file for ${task.name}: ${jsonFileName}"
        }
    }
    
    /**
     * Create a JSON report for a completed task.
     */
    Map createTaskReport(TaskRun task, TraceRecord trace) {
        final processName = task.processor.name
        final tag = trace.get('tag') as String

        // Get output file paths grouped by emit names
        final outputParams = task.getOutputsByType(FileOutParam)
        final Map<String, List<Path>> outputsByEmit = groupOutputsByEmit(outputParams)

        if (outputsByEmit.isEmpty()) {
            log.trace "No output files found for task ${task.name}"
            return null
        }

        // Create JSON content
        final jsonContent = [
            process: processName,
            tag: tag,
            taskName: task.name,
            workDir: task.workDir.toString(),
            outputs: [:],
            timestamp: new Date().toString()
        ]

        // Populate file paths grouped by emit names
        populateFilePathsByEmit(jsonContent, outputsByEmit)
        
        final totalFiles = outputsByEmit.values().flatten().size()
        log.debug "Created report for ${task.name} with ${totalFiles} files grouped by emit names"
        
        // Store for collation if needed
        synchronized(allTaskReports) {
            allTaskReports << jsonContent
        }
        
        return jsonContent
    }
    
    /**
     * Generate collated report of all tasks.
     */
    Map createCollatedReport() {
        synchronized(allTaskReports) {
            final taskList = new ArrayList(allTaskReports)
            
            // Update published files for all tasks at collation time
            updatePublishedFilesInReports(taskList)
            
            return [
                workflow: [
                    totalTasks: taskList.size(),
                    timestamp: new Date().toString()
                ],
                tasks: taskList
            ]
        }
    }
    
    /**
     * Update published files information in task reports.
     */
    private void updatePublishedFilesInReports(List<Map> taskReports) {
        synchronized(publishedPathMap) {
            log.info "FileReportCollector: Updating published files in ${taskReports.size()} task reports"
            log.info "FileReportCollector: publishedPathMap contains ${publishedPathMap.size()} mappings"
            publishedPathMap.each { source, destinations ->
                log.info "FileReportCollector: ${source} -> ${destinations}"
            }
            
            taskReports.each { taskReport ->
                final taskName = taskReport.taskName
                log.info "FileReportCollector: Processing task ${taskName}"
                final outputsMap = (Map)taskReport.outputs
                outputsMap.each { emitName, emitData ->
                    final emitMap = (Map)emitData
                    final workDirFiles = emitMap.workDirFiles as List<String>
                    final publishedFiles = emitMap.publishedFiles as List<String>
                    
                    log.info "FileReportCollector: Emit ${emitName} has workDirFiles: ${workDirFiles}"
                    
                    // Clear and repopulate published files for this emit
                    publishedFiles.clear()
                    workDirFiles.each { workFileStr ->
                        final workFile = Paths.get(workFileStr)
                        if (publishedPathMap.containsKey(workFile)) {
                            final destinations = publishedPathMap[workFile].collect{ it.toString() }
                            publishedFiles.addAll(destinations)
                            log.info "FileReportCollector: Found published files for ${workFile}: ${destinations}"
                        } else {
                            log.info "FileReportCollector: No published files found for ${workFile}"
                        }
                    }
                    
                    log.info "FileReportCollector: Final publishedFiles for emit ${emitName}: ${publishedFiles}"
                }
            }
        }
    }
    
    /**
     * Update all individual JSON files with published file information.
     */
    void updateIndividualJsonFiles() {
        synchronized(taskJsonFiles) {
            log.info "FileReportCollector: Updating ${taskJsonFiles.size()} individual JSON files with published files"
            taskJsonFiles.each { task, jsonFileName ->
                try {
                    // Find the task report for this task
                    Map taskReport = null
                    synchronized(allTaskReports) {
                        taskReport = allTaskReports.find { report ->
                            report.taskName == task.name || 
                            (report.process == task.processor.name && report.workDir == task.workDir.toString())
                        }
                    }
                    
                    if (taskReport) {
                        // Update published files in this report
                        updatePublishedFilesInReports([taskReport])
                        
                        // Rewrite the individual JSON file with updated data
                        log.info "FileReportCollector: Rewriting individual JSON file for ${task.name}: ${jsonFileName}"
                        JsonFileWriter.writeToPublishDirs(task, jsonFileName, taskReport)
                    } else {
                        log.warn "FileReportCollector: Could not find task report for ${task.name}"
                    }
                } catch (Exception e) {
                    log.warn "FileReportCollector: Failed to update individual JSON file for ${task.name}", e
                }
            }
        }
    }
    
    /**
     * Check if a source file has been published.
     */
    boolean isFilePublished(Path sourcePath) {
        synchronized(publishedPathMap) {
            return publishedPathMap.containsKey(sourcePath)
        }
    }
    
    /**
     * Get published paths for a source file.
     */
    Set<Path> getPublishedPaths(Path sourcePath) {
        synchronized(publishedPathMap) {
            return publishedPathMap.get(sourcePath, [] as Set)
        }
    }
    
    /**
     * Group output files by their emit names.
     */
    private Map<String, List<Path>> groupOutputsByEmit(Map outputParams) {
        final Map<String, List<Path>> outputsByEmit = [:]
        int outputIndex = 0
        
        outputParams.each { param, files ->
            String emitName = extractEmitName(param, outputIndex)
            final fileList = files instanceof List ? files : [files]
            final List<Path> pathList = fileList.collect { file ->
                file instanceof Path ? file : Paths.get(file.toString())
            }
            outputsByEmit[emitName] = pathList
            outputIndex++
        }
        
        return outputsByEmit
    }
    
    /**
     * Extract emit name from FileOutParam object.
     */
    private String extractEmitName(Object param, int index) {
        // Try to get the emit name from the parameter
        try {
            log.debug "Extracting emit name from param: ${param?.getClass()?.name} - ${param?.toString()}"
            
            // Try to extract from string representation first (most reliable)
            String paramStr = param?.toString()
            if (paramStr && paramStr.contains('emit:')) {
                // Extract emit name from string like "path 'file.txt', emit: result"
                def matcher = paramStr =~ /emit:\s*(\w+)/
                if (matcher.find()) {
                    String emitName = matcher.group(1)
                    log.debug "Found emit name from string: ${emitName}"
                    return emitName
                }
            }
            
            // Use reflection to safely access properties
            def metaClass = param.getClass().metaClass
            
            // Check common property names for emit
            def propertyNames = ['channelEmitName', 'emit', 'name']
            for (String propName : propertyNames) {
                try {
                    def property = metaClass.getProperty(param, propName)
                    if (property && property.toString().trim()) {
                        String emitName = property.toString().trim()
                        log.debug "Found emit name from property ${propName}: ${emitName}"
                        return emitName
                    }
                } catch (Exception ignored) {
                    // Property doesn't exist or can't be accessed
                }
            }
            
        } catch (Exception e) {
            log.debug "Failed to extract emit name from param: ${e.message}"
        }
        
        // Fallback to indexed output name
        String fallback = "output_${index}"
        log.debug "Using fallback emit name: ${fallback}"
        return fallback
    }
    
    /**
     * Populate file paths in the JSON content grouped by emit names.
     */
    private void populateFilePathsByEmit(Map jsonContent, Map<String, List<Path>> outputsByEmit) {
        synchronized(publishedPathMap) {
            outputsByEmit.each { emitName, workDirFiles ->
                final workDirPaths = []
                final publishedPaths = []
                
                for (Path workFile : workDirFiles) {
                    workDirPaths << workFile.toString()
                    if (publishedPathMap.containsKey(workFile)) {
                        publishedPaths.addAll(
                            publishedPathMap[workFile].collect{ it.toString() }
                        )
                    }
                }
                
                ((Map)jsonContent.outputs)[emitName] = [
                    workDirFiles: workDirPaths,
                    publishedFiles: publishedPaths
                ]
            }
        }
    }
    
    /**
     * Populate file paths in the JSON content (legacy method for backward compatibility).
     */
    private void populateFilePaths(Map jsonContent, List<Path> workDirOutputFiles) {
        synchronized(publishedPathMap) {
            for (Path workFile : workDirOutputFiles) {
                ((List<String>)((Map)jsonContent.outputs).workDirFiles).add(workFile.toString())
                if (publishedPathMap.containsKey(workFile)) {
                    ((List<String>)((Map)jsonContent.outputs).publishedFiles).addAll(
                        publishedPathMap[workFile].collect{ it.toString() }
                    )
                }
            }
        }
    }
}