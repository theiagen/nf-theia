# nf-theia

A Nextflow plugin for comprehensive file tracking and reporting, providing detailed information about workflow outputs including both named (emit) and unnamed outputs with multi-cloud storage support.

## Features

- **File Output Tracking**: Monitors all process outputs during workflow execution
- **Named Output Support**: Tracks outputs with `emit` names for easy identification
- **Individual Process Reports**: Generates individual JSON reports for each process/tag combination
- **Published File Tracking**: Automatically tracks both work directory and published file locations
- **JSON Reporting**: Generates structured JSON reports with file locations and metadata
- **Collated Reports**: Option to generate single consolidated report for entire workflow
- **Multi-Cloud Storage Support**: Built-in support for multiple cloud storage backends:
  - **Amazon S3**: `s3://bucket/path`
  - **Google Cloud Storage**: `gs://bucket/path`  
  - **Azure Blob Storage**: `azure://container/path`
  - **Latch Data**: `latch://workspace.account/path`
  - **Local filesystem**: `/local/path`

## Quick Start

1. **Clone and build**:
   ```bash
   git clone https://github.com/theiagen/nf-theia.git
   cd nf-theia
   ./gradlew build
   ```

2. **Add to your workflow**:
   ```groovy
   // In your nextflow.config
   plugins {
       id 'nf-theia@0.2.0'
   }
   
   theia {
       fileReport.enabled = true
       fileReport.collate = true
   }
   ```

3. **Run your workflow** - reports will be generated automatically in your publishDir locations!

## Installation

### Local Installation (Development)

1. **Prerequisites**:
   - Java 11+ (tested with Java 17)
   - Gradle 7.0+
   - Nextflow 24.10.0+
   - Git

2. **Clone the repository**:
   ```bash
   git clone https://github.com/theiagen/nf-theia.git
   cd nf-theia
   ```

3. **Build the plugin**:
   ```bash
   ./gradlew build
   ```

4. **Create plugin distribution** (optional):
   ```bash
   ./gradlew makeZip  # Creates nf-theia-0.1.0.zip in build/libs/
   ```

5. **Install plugin locally for use**:
   ```bash
   ./gradlew installPlugin  # Installs to ~/.nextflow/plugins/
   ```

### Distribution Installation

1. **Test repository** (alternative for development):
   ```bash
   export NXF_PLUGINS_TEST_REPOSITORY="https://github.com/theiagen/nf-theia/releases/download/v0.2.0/nf-theia-0.2.0-meta.json"
   nextflow run your_workflow.nf
   ```

2. **Manual plugin installation**:
   - The plugin must be extracted as a folder in `~/.nextflow/plugins/`
   - Use `./gradlew installPlugin` for local development builds (recommended)

## Usage

### Configuration

Add the plugin configuration to your `nextflow.config` file:

```groovy
plugins {
    id 'nf-theia@0.2.0'
}

theia {
    fileReport {
        enabled = true
        collate = true
        collatedFileName = "collated-workflow-files.json"
    }
}
```

### Configuration Options

- **`enabled`** (boolean): Enable/disable file reporting (default: `false`)
- **`collate`** (boolean): Generate a single collated report file (default: `false`)
- **`collatedFileName`** (string): Name of the collated report file (default: `"colalted-workflow-files.json"`)

### Cloud Storage Configuration

The plugin automatically detects and handles different storage backends based on the publishDir paths in your workflow:

#### Amazon S3
```groovy
process EXAMPLE {
    publishDir 's3://my-bucket/results', mode: 'copy'
    // ... process definition
}
```

#### Google Cloud Storage
```groovy
process EXAMPLE {
    publishDir 'gs://my-bucket/results', mode: 'copy'
    // ... process definition
}
```

#### Azure Blob Storage
```groovy
process EXAMPLE {
    publishDir 'azure://container/results', mode: 'copy'
    // ... process definition
}
```

#### Latch Data
```groovy
process EXAMPLE {
    publishDir 'latch://workspace.account/results', mode: 'copy'
    // ... process definition
}
```

**Note**: Ensure your environment has proper authentication configured for your chosen cloud storage provider (AWS credentials, Google Cloud SDK, Azure CLI, or Latch SDK).

## Architecture

### File System Integration

The plugin leverages Nextflow's custom file system provider architecture to support multiple storage backends seamlessly:

```
User Workflow
     ↓
publishDir: "latch://workspace.account/results"
     ↓
nf-theia Plugin
     ↓
CloudFileUtils.isLatchPath() → true
     ↓
LatchFileWriter.writeToLatch()
     ↓
java.nio.file.Files.write()
     ↓
LatchFileSystemProvider (from latch/ folder)
     ↓
Latch Data Platform
```

### Key Components

1. **CloudFileUtils**: Detects storage backend from URL schemes
2. **FileWriter Classes**: Route operations to appropriate cloud providers  
3. **FileReportCollector**: Aggregates file information across tasks
4. **FileReportObserver**: Monitors workflow execution events
5. **Custom File Systems**: Handle cloud-specific operations transparently

This architecture allows the plugin to treat all storage backends uniformly while leveraging Nextflow's built-in cloud storage capabilities.

2. **Run your workflow** as normal:
   ```bash
   nextflow run your_workflow.nf
   ```

3. **Check the generated reports**:
   - Individual JSON reports are generated for each process/tag in their respective publishDir locations
   - If `collate = true`, a single consolidated JSON file will be created in the root publishDir location with all file information

### Example Workflow

```groovy
#!/usr/bin/env nextflow

process ANALYZE_DATA {
    publishDir 'results/analysis', mode: 'copy'
    tag "${sample_id}"
    
    input:
    val sample_id
    
    output:
    path "${sample_id}_analysis.txt", emit: analysis
    path "${sample_id}_summary.txt", emit: summary
    path "${sample_id}_raw.txt"

    script:
    """
    echo "Analysis results for ${sample_id}" > ${sample_id}_analysis.txt
    echo "Summary data for ${sample_id}" > ${sample_id}_summary.txt
    echo "Raw output for ${sample_id}" > ${sample_id}_raw.txt
    """
}

workflow {
    samples = Channel.of('sample_001', 'sample_002')
    ANALYZE_DATA(samples)
}
```

With the plugin enabled, this will generate:
- **Individual JSON files**: `results/analysis/ANALYZE_DATA_sample_001.json`, `ANALYZE_DATA_sample_002.json`
- **Collated JSON file**: `results/workflow_files.json` (if `collate = true`)
- **Named outputs**: `analysis` and `summary` 
- **Unnamed output**: `output_2` (for raw file)
- **Published file paths**: Automatically tracked in `publishedFiles` arrays

### Report Structure

The generated JSON report contains:

```json
{
    "workflow": {
        "totalTasks": 2,
        "timestamp": "Sat Jul 19 22:00:29 BST 2025"
    },
    "tasks": [
        {
            "process": "ANALYZE_DATA",
            "tag": "sample_001",
            "taskName": "ANALYZE_DATA (sample_001)",
            "workDir": "/path/to/work/dir/sample_001",
            "outputs": {
                "analysis": {
                    "workDirFiles": ["/path/to/work/dir/sample_001_analysis.txt"],
                    "publishedFiles": ["/path/to/results/analysis/sample_001_analysis.txt"]
                },
                "summary": {
                    "workDirFiles": ["/path/to/work/dir/sample_001_summary.txt"],
                    "publishedFiles": ["/path/to/results/analysis/sample_001_summary.txt"]
                },
                "output_2": {
                    "workDirFiles": ["/path/to/work/dir/sample_001_raw.txt"],
                    "publishedFiles": ["/path/to/results/analysis/sample_001_raw.txt"]
                }
            },
            "timestamp": "Sat Jul 19 22:00:29 BST 2025"
        }
    ]
}
```

## Troubleshooting

### No Reports Generated

If no reports are generated:

1. Verify `theia.fileReport.enabled = true` in your config
2. Check that your workflow actually produces outputs
3. Ensure you have write permissions to the output directory
4. Check the Nextflow log for plugin loading messages

### Cloud Storage Issues

#### Amazon S3
- Verify AWS credentials: `aws configure list`
- Check bucket permissions and region settings
- Ensure S3 paths use correct format: `s3://bucket/path`

#### Google Cloud Storage
- Authenticate: `gcloud auth application-default login`
- Verify project and bucket access permissions
- Ensure GS paths use correct format: `gs://bucket/path`

#### Azure Blob Storage
- Login: `az login`
- Check storage account and container permissions
- Ensure Azure paths use correct format: `azure://container/path`

#### Latch Data
- Verify Latch authentication: Check `~/.latch/token` exists
- For Latch runtime: `FLYTE_INTERNAL_EXECUTION_ID` should be set
- Ensure Latch paths use correct format: `latch://workspace.account/path`

### Plugin Build Issues

If you encounter build issues:

1. **Java Version Compatibility**:
   ```bash
   java -version  # Should be Java 11+
   ```

2. **Clean and rebuild**:
   ```bash
   ./gradlew clean build
   ```

3. **Dependency issues**:
   ```bash
   ./gradlew dependencies --configuration runtimeClasspath
   ```

## Development Status

- ✅ **Core functionality**: File tracking and JSON reporting
- ✅ **Multi-cloud support**: S3, GCS, Azure, Latch, and local storage  
- ✅ **Named outputs**: Full support for `emit` parameters
- ✅ **Collated reports**: Workflow-level consolidated reports
- ✅ **Build system**: Standard Nextflow plugin build pipeline
- 🚧 **Plugin registry**: Not yet published to official registry
- 🚧 **Test coverage**: Basic integration tests available

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for:
- Development setup and build instructions
- Architecture documentation for custom file systems
- Testing guidelines and procedures
- Code style and submission guidelines

## Support

- **Issues**: Report bugs and request features via [GitHub Issues](https://github.com/theiagen/nf-theia/issues)
- **Discussions**: Ask questions in [GitHub Discussions](https://github.com/theiagen/nf-theia/discussions)
- **Documentation**: See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed development docs

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) file for details.

---

**nf-theia** - Comprehensive file tracking for Nextflow workflows across any storage backend.