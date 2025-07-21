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

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Utility class for handling cloud storage paths and operations.
 *
 * @author Thanh Le Viet <thanh.le.viet@theiagen.com>
 */
@Slf4j
@CompileStatic
class CloudFileUtils {

    /**
     * Check if a path is an S3 path.
     */
    static boolean isS3Path(Path path) {
        return path.toString().startsWith('s3://')
    }

    /**
     * Check if a path is a Google Cloud Storage path.
     */
    static boolean isGcsPath(Path path) {
        return path.toString().startsWith('gs://')
    }

    /**
     * Check if a path is an Azure Blob Storage path.
     */
    static boolean isAzurePath(Path path) {
        return path.toString().startsWith('abfs://') || path.toString().startsWith('azure://')
    }

    /**
     * Check if a path is a Latch path.
     */
    static boolean isLatchPath(Path path) {
        return path.toString().startsWith('latch://')
    }

    /**
     * Check if a path is any cloud storage path.
     */
    static boolean isCloudPath(Path path) {
        return isS3Path(path) || isGcsPath(path) || isAzurePath(path) || isLatchPath(path)
    }

    /**
     * Parse S3 path to extract bucket and key.
     * @param s3Path The S3 path (e.g., s3://bucket/path/to/file)
     * @return Map with 'bucket' and 'key' entries
     */
    static Map<String, String> parseS3Path(String s3Path) {
        if (!s3Path.startsWith('s3://')) {
            throw new IllegalArgumentException("Not an S3 path: $s3Path")
        }
        
        final pathWithoutProtocol = s3Path.substring(5) // Remove 's3://'
        final slashIndex = pathWithoutProtocol.indexOf('/')
        
        if (slashIndex == -1) {
            // Just bucket, no key
            return [bucket: pathWithoutProtocol, key: '']
        }
        
        final bucket = pathWithoutProtocol.substring(0, slashIndex)
        final key = pathWithoutProtocol.substring(slashIndex + 1)
        
        return [bucket: bucket, key: key]
    }
}