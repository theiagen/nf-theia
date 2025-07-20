# nf-theia

A Nextflow plugin for file tracking and reporting, providing detailed information about workflow outputs including both named (emit) and unnamed outputs.

## Features

- **File Output Tracking**: Monitors all process outputs during workflow execution
- **Named Output Support**: Tracks outputs with `emit` names for easy identification
- **Individual Process Reports**: Generates individual JSON reports for each process/tag combination
- **Published File Tracking**: Automatically tracks both work directory and published file locations
- **JSON Reporting**: Generates structured JSON reports with file locations and metadata
- **Collated Reports**: Option to generate single consolidated report for entire workflow
- **Cloud Storage Support**: Built-in support for S3 and other cloud storage backends

## Installation

Since this plugin is not available in the official Nextflow plugin registry, you need to install it manually.

### Local Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/theiagen/nf-theia.git
   cd nf-theia
   ```

2. **Build the plugin**:
   ```bash
   ./gradlew buildPlugin
   ```

3. **Install locally**:
   ```bash
   ./gradlew installPlugin
   ```

## Usage

### Configuration

Add the plugin configuration to your `nextflow.config` file:

```groovy
plugins {
    id 'nf-theia@0.1.0'
}

theia {
    fileReport {
        enabled = true
        collate = true
        collatedFileName = "colalted-workflow-files.json"
    }
}
```

### Configuration Options

- **`enabled`** (boolean): Enable/disable file reporting (default: `false`)
- **`collate`** (boolean): Generate a single collated report file (default: `false`)
- **`collatedFileName`** (string): Name of the collated report file (default: `"colalted-workflow-files.json"`)

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

### Permission Issues

For cloud storage issues:
1. Verify your cloud credentials are properly configured
2. Check bucket permissions for write access
3. Ensure the cloud storage path is valid

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/theiagen/nf-theia).

## License

Licensed under the Apache License, Version 2.0. See LICENSE file for details.