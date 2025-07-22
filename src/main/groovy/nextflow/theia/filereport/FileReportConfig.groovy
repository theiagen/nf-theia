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
import nextflow.Session

/**
 * Configuration for file report functionality.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@CompileStatic
class FileReportConfig {
    
    boolean enabled = false
    boolean collate = false
    boolean workdir = false
    String collatedFileName = "workflow_files.json"
    
    static FileReportConfig fromSession(Session session) {
        final config = new FileReportConfig()
        final fileReportConfig = session.config.navigate('theia.fileReport')
        
        if (fileReportConfig == true) {
            config.enabled = true
        } else if (fileReportConfig instanceof Map) {
            config.enabled = true
            config.collate = fileReportConfig.collate ?: false
            config.workdir = fileReportConfig.workdir ?: false
            config.collatedFileName = fileReportConfig.collatedFileName ?: "workflow_files.json"
        }
        
        return config
    }
    
    static boolean isEnabled(Map config) {
        final fileReportConfig = config.navigate('theia.fileReport')
        return fileReportConfig == true || fileReportConfig instanceof Map
    }
}