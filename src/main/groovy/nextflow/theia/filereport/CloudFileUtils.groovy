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

    /**
     * Get the original URL string from a Path that might have had its protocol stripped.
     * This is needed because Nextflow's Path objects strip protocols like latch://.
     * 
     * @param path The Path object that may have been stripped
     * @param originalStr The original string representation if available
     * @return The full URL string with protocol preserved
     */
    static String getFullPathString(Path path, String originalStr = null) {
        final pathStr = path.toString()
        
        // If original string is provided and looks like a latch path, use it
        if (originalStr && originalStr.startsWith('latch://')) {
            return originalStr
        }
        
        // If path string doesn't start with / but looks like it was stripped from latch://
        // We need to check the path's file system or other indicators
        if (!pathStr.startsWith('/') && !pathStr.contains('://')) {
            // This might be a stripped latch path, but we can't reconstruct it without more info
            // Return as-is and let the caller handle it
            return pathStr
        }
        
        return pathStr
    }
    
    /**
     * Check if a path string appears to be a stripped latch path.
     * Latch paths when stripped typically look like "38627.account/path..." with any number of subdirectories
     */
    static boolean isStrippedLatchPath(String pathStr) {
        // Look for pattern that suggests this was a latch path: starts with number.text/
        // Examples: 
        // - "38627.account/json_builder_test" (from latch://38627.account/json_builder_test)
        // - "38627.account/json_builder_test/sub1/" (from latch://38627.account/json_builder_test/sub1/)
        // - "38627.account/json_builder_test/sub1/sub2/" (from latch://38627.account/json_builder_test/sub1/sub2/)
        return pathStr.matches(/^\d+\.[a-zA-Z_][a-zA-Z0-9_]*\//) && !pathStr.startsWith('/')
    }

    /**
     * Reconstruct a latch URL from a stripped path.
     * If the path looks like it was stripped from latch://, reconstruct the full URL.
     * 
     * @param pathStr The potentially stripped path string
     * @return Full latch:// URL if it appears to be stripped, otherwise original string
     */
    static String reconstructLatchUrl(String pathStr) {
        if (isStrippedLatchPath(pathStr)) {
            return "latch://" + pathStr
        }
        return pathStr
    }

    /**
     * Get the proper path string for cloud storage operations.
     * This handles the case where latch:// URLs have been stripped by Path conversion.
     * 
     * @param path The Path object
     * @return Proper path string for cloud operations
     */
    static String getCloudPathString(Path path) {
        final pathStr = path.toString()
        
        // Check if this looks like a stripped latch path and reconstruct
        if (isStrippedLatchPath(pathStr)) {
            return reconstructLatchUrl(pathStr)
        }
        
        return pathStr
    }
}