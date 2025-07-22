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
import nextflow.script.params.TupleOutParam
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
    // Use normalized path strings as keys to avoid Path object comparison issues
    private final Map<String, Set<String>> publishedPathMap = [:].asSynchronized()
    
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
        log.debug "FileReportCollector: Recording file publish: ${source} -> ${destination}"
        
        // Normalize paths to strings to avoid Path object comparison issues
        final sourceKey = source.toString()
        final destinationStr = destination.toString()
        
        synchronized(publishedPathMap) {
            if (!publishedPathMap.containsKey(sourceKey)) {
                publishedPathMap[sourceKey] = [] as Set
            }
            publishedPathMap[sourceKey] << destinationStr
            log.debug "FileReportCollector: publishedPathMap now contains ${publishedPathMap.size()} source files"
            log.debug "FileReportCollector: Source ${sourceKey} maps to ${publishedPathMap[sourceKey]}"
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
            log.debug "FileReportCollector: Recorded individual JSON file for ${task.name}: ${jsonFileName}"
        }
    }
    

    /**
     * Create a JSON report for a completed task.
     */
    Map createTaskReport(TaskRun task, TraceRecord trace) {
        final processName = task.processor.name
        final tag = trace.get('tag') as String

        // Try to get original output definitions before decomposition
        log.debug "Attempting to get original output definitions for task ${task.name}"
        final Map<String, List<Path>> outputsByEmit = tryGetOriginalOutputDefinitionsNew(task)
        
        // Fallback to decomposed outputs if original definitions not accessible
        if (outputsByEmit.isEmpty()) {
            log.debug "Original output definitions not accessible, falling back to decomposed outputs"
            final outputParams = task.getOutputsByType(FileOutParam)
            final tupleOutputParams = task.getOutputsByType(TupleOutParam)
            outputsByEmit.putAll(groupOutputsByEmit(outputParams, tupleOutputParams, task))
        } else {
            log.debug "Successfully retrieved ${outputsByEmit.size()} output groups from original definitions"
        }

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
            log.debug "FileReportCollector: Updating published files in ${taskReports.size()} task reports"
            log.debug "FileReportCollector: publishedPathMap contains ${publishedPathMap.size()} mappings"
            publishedPathMap.each { source, destinations ->
                log.debug "FileReportCollector: ${source} -> ${destinations}"
            }
            
            taskReports.each { taskReport ->
                final taskName = taskReport.taskName
                log.debug "FileReportCollector: Processing task ${taskName}"
                final outputsMap = (Map)taskReport.outputs
                outputsMap.each { emitName, emitData ->
                    final emitMap = (Map)emitData
                    final workDirFiles = emitMap.workDirFiles as List<String>
                    final publishedFiles = emitMap.publishedFiles as List<String>
                    
                    log.debug "FileReportCollector: Emit ${emitName} has workDirFiles: ${workDirFiles}"
                    
                    // Clear and repopulate published files for this emit
                    publishedFiles.clear()
                    workDirFiles.each { workFileStr ->
                        if (publishedPathMap.containsKey(workFileStr)) {
                            final destinations = publishedPathMap[workFileStr]
                            publishedFiles.addAll(destinations)
                            log.debug "FileReportCollector: Found published files for ${workFileStr}: ${destinations}"
                        } else {
                            log.debug "FileReportCollector: No published files found for ${workFileStr}"
                        }
                    }
                    
                    log.debug "FileReportCollector: Final publishedFiles for emit ${emitName}: ${publishedFiles}"
                }
            }
        }
    }
    
    /**
     * Update all individual JSON files with published file information.
     */
    void updateIndividualJsonFiles() {
        synchronized(taskJsonFiles) {
            log.debug "FileReportCollector: Updating ${taskJsonFiles.size()} individual JSON files with published files"
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
                        log.debug "FileReportCollector: Rewriting individual JSON file for ${task.name}: ${jsonFileName}"
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
            return publishedPathMap.containsKey(sourcePath.toString())
        }
    }
    
    /**
     * Get published paths for a source file.
     */
    Set<String> getPublishedPaths(Path sourcePath) {
        synchronized(publishedPathMap) {
            return publishedPathMap.get(sourcePath.toString(), [] as Set)
        }
    }
    
    /**
     * Build a mapping of output index to emit name from the processor's output definitions.
     */
    private Map<Integer, String> getEmitNameMap(TaskRun task) {
        Map<Integer, String> emitNames = new LinkedHashMap<Integer, String>()
        try {
            def processor = task?.processor
            def outputs = processor?.config?.getOutputs()
            outputs?.eachWithIndex { output, idx ->
                def emitName = null
                try { emitName = output?.channelEmitName } catch (ignored) {}
                if (!emitName) {
                    def metaProp = output?.metaClass?.hasProperty(output, 'emit')
                    if (metaProp) {
                        emitName = output.metaClass.getProperty(output, 'emit')
                    }
                }
                emitNames.put((Integer)idx, (String)(emitName ? emitName.toString() : "output_${idx}"))
            }
        } catch (Exception e) {
            log.warn "Failed to build emit name map: ${e.message}", e
        }
        return emitNames
    }

    /**
     * Group output files by their emit names using the emit name map.
     */
    private Map<String, List<Path>> groupOutputsByEmit(Map outputParams, Map tupleOutputParams, TaskRun task) {
        final Map<String, List<Path>> outputsByEmit = [:]
        
        log.debug "groupOutputsByEmit called with:"
        log.debug "  outputParams.size(): ${outputParams.size()}"
        log.debug "  tupleOutputParams.size(): ${tupleOutputParams.size()}"
        outputParams.each { param, files ->
            log.debug "  FileOutParam: ${param?.getClass()?.name} - ${param?.toString()}"
        }
        tupleOutputParams.each { param, files ->
            log.debug "  TupleOutParam: ${param?.getClass()?.name} - ${param?.toString()}"
        }
        
        // Use the emit name map for all outputs
        Map<Integer, String> emitNamesByIndex = getEmitNameMap(task)
        log.debug "Emit name map: ${emitNamesByIndex}"
        
        int outputIndex = 0
        outputParams.each { param, files ->
            String emitName = emitNamesByIndex[outputIndex]
            final fileList = files instanceof List ? files : [files]
            final List<Path> pathList = fileList.collect { file ->
                file instanceof Path ? file : Paths.get(file.toString())
            }
            outputsByEmit[emitName] = pathList
            log.debug "Mapped FileOutParam at index ${outputIndex} to emit name '${emitName}' with ${pathList.size()} files"
            outputIndex++
        }
        
        tupleOutputParams.each { param, files ->
            String emitName = emitNamesByIndex[outputIndex]
            final fileList = files instanceof List ? files : [files]
            final List<Path> pathList = []
            fileList.each { file ->
                if (file instanceof Path) {
                    pathList << file
                } else {
                    try {
                        final pathCandidate = Paths.get(file.toString())
                        if (file.toString().contains('.') || file.toString().contains('/')) {
                            pathList << pathCandidate
                        }
                    } catch (Exception e) {
                        log.debug "Skipping non-path element from TupleOutParam: ${file} (${e.message})"
                    }
                }
            }
            if (!pathList.isEmpty()) {
                outputsByEmit[emitName] = pathList
                log.debug "Mapped TupleOutParam at index ${outputIndex} to emit name '${emitName}' with ${pathList.size()} files"
            }
            outputIndex++
        }
        
        return outputsByEmit
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
                    final workFileStr = workFile.toString()
                    workDirPaths << workFileStr
                    if (publishedPathMap.containsKey(workFileStr)) {
                        publishedPaths.addAll(publishedPathMap[workFileStr])
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
     * Try to get original output definitions from TaskProcessor before decomposition.
     */
    private Map<String, List<Path>> tryGetOriginalOutputDefinitionsNew(TaskRun task) {
        final Map<String, List<Path>> outputsByEmit = [:]
        
        try {
            log.debug "Attempting to access original output definitions via TaskProcessor"
            
            // Build emit name mapping from processor's original configuration
            Map<Integer, String> emitNamesByIndex = getEmitNameMap(task)
            
            if (!emitNamesByIndex.isEmpty()) {
                log.debug "Built emit name mapping: ${emitNamesByIndex}"
                return mapDecomposedOutputsByEmitNames(task, emitNamesByIndex)
            } else {
                log.debug "Could not build emit name mapping"
            }
            
        } catch (Exception e) {
            log.debug "Failed to access original output definitions: ${e.message}"
        }
        
        return outputsByEmit
    }
    

    
    
    /**
     * Map decomposed outputs to emit names using the built mapping.
     */
    private Map<String, List<Path>> mapDecomposedOutputsByEmitNames(TaskRun task, Map<Integer, String> emitNamesByIndex) {
        final Map<String, List<Path>> outputsByEmit = [:]
        
        try {
            // Get all decomposed outputs
            final decomposedFileOuts = task.getOutputsByType(FileOutParam)
            
            // Group decomposed outputs by their original index (extracted from <x:y> pattern)
            Map<Integer, List<Path>> filesByOriginalIndex = [:]
            
            decomposedFileOuts.each { param, files ->
                String paramStr = param.toString()
                log.debug "Processing decomposed FileOutParam: ${paramStr}"
                
                // Extract original index from parameter string pattern like <0:0>, <1:1>, <2:1>
                def matcher = paramStr =~ /<(\d+):(\d+)>/
                if (matcher.find()) {
                    int originalIndex = Integer.parseInt(matcher.group(1))
                    
                    if (!filesByOriginalIndex.containsKey(originalIndex)) {
                        filesByOriginalIndex[originalIndex] = []
                    }
                    
                    // Add files from this decomposed param to the original index group
                    def fileList = files instanceof List ? files : [files]
                    fileList.each { file ->
                        if (file instanceof Path) {
                            filesByOriginalIndex[originalIndex].add(file)
                        } else if (file.toString().contains('.') || file.toString().contains('/')) {
                            // Only include file-like strings (not val elements)
                            filesByOriginalIndex[originalIndex].add(Paths.get(file.toString()))
                        }
                    }
                }
            }
            
            log.debug "Files grouped by original index: ${filesByOriginalIndex.collectEntries { k, v -> [k, v.size()] }}"
            
            // Map files to emit names using the index mapping
            filesByOriginalIndex.each { originalIndex, files ->
                String emitName = emitNamesByIndex[originalIndex] ?: "output_${originalIndex}".toString()
                if (!files.isEmpty()) {
                    outputsByEmit[emitName] = files
                    log.debug "Mapped ${files.size()} files to emit name '${emitName}' for index ${originalIndex}"
                }
            }
            
        } catch (Exception e) {
            log.debug "Failed to map decomposed outputs by emit names: ${e.message}"
        }
        
        return outputsByEmit
    }
}