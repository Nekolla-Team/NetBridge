package top.tangge233.netbridge.benchmark.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Per-task invocation manifest written under {@code build/results/current/<taskName>.json}.
 *
 * <p>Marks the raw result file produced by the latest run of a user-facing task so report
 * rendering never scans a directory guessing the newest file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvocationManifest(
    val task: String,
    val suite: String,
    val rawResult: String
)
