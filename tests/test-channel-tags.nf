#!/usr/bin/env nextflow

process ANALYZE_SAMPLE {
    publishDir "test_results/samples/${sample_id}", mode: 'copy'
    tag "${sample_id}"
    
    input:
    val sample_id
    
    output:
    path "${sample_id}_analysis.txt", emit: analysis
    path "${sample_id}_summary.txt", emit: summary
    path "${sample_id}_report.pdf", emit: report

    script:
    """
    echo "Analysis results for sample ${sample_id}" > ${sample_id}_analysis.txt
    echo "Summary for ${sample_id}: processed successfully" > ${sample_id}_summary.txt
    echo "PDF report placeholder for ${sample_id}" > ${sample_id}_report.pdf
    """
}

process QC_CHECK {
    publishDir "test_results/qc/${sample_id}", mode: 'copy'
    tag "${sample_id}_qc"
    
    input:
    val sample_id
    path analysis_file
    
    output:
    path "${sample_id}_qc_passed.txt", emit: qc_result
    tuple val(sample_id), path("${sample_id}_qc_metrics.json"), emit: metrics

    script:
    """
    echo "QC check passed for ${sample_id}" > ${sample_id}_qc_passed.txt
    echo '{"sample": "${sample_id}", "qc_score": 95, "status": "PASS"}' > ${sample_id}_qc_metrics.json
    """
}

workflow {
    // Create a channel with multiple sample IDs
    samples_ch = Channel.of(
        'sample_001',
        'sample_002', 
        'sample_003',
        'control_neg',
        'control_pos'
    )
    
    // Run analysis on each sample - tag will be the sample ID
    analysis_results = ANALYZE_SAMPLE(samples_ch)
    
    // Run QC check using the analysis results - tag will be sample_id + "_qc"
    qc_results = QC_CHECK(
        samples_ch,
        analysis_results.analysis
    )
}