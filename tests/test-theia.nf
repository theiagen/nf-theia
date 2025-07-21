#!/usr/bin/env nextflow

process TEST_EMIT_NAMES {
    publishDir 'test_results/emit_names', mode: 'copy'
    tag "hello"
    output:
    path "output1.txt", emit: first
    path "output2.txt", emit: second
    path "output3.txt", emit: report

    script:
    """
    echo "This is first output" > output1.txt
    echo "This is second output" > output2.txt
    echo "This is report output" > output3.txt
    """
}

process TEST_NO_EMIT {
    publishDir 'test_results/no_emit', mode: 'copy'
    
    output:
    path "no_emit1.txt"
    path "no_emit2.txt"

    script:
    """
    echo "This has no emit 1" > no_emit1.txt
    echo "This has no emit 2" > no_emit2.txt
    """
}

process TEST_MIXED_OUTPUTS {
    publishDir 'test_results/mixed', mode: 'copy'
    
    output:
    path "mixed1.txt", emit: named_output
    path "mixed2.txt"
    path "mixed3.txt", emit: another_named

    script:
    """
    echo "This is named output" > mixed1.txt
    echo "This has no emit" > mixed2.txt
    echo "This is another named" > mixed3.txt
    """
}

workflow {
    TEST_EMIT_NAMES()
    TEST_NO_EMIT()
    TEST_MIXED_OUTPUTS()
}