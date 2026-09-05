package com.kiss.backend.model.entity

/**
 * Single source of truth for processing status across the ledger, DTOs, SSE
 * and the reconciler. Coarse by design: fine-grained progress (queue position,
 * per-poll) lives on the live workflow query / SSE stream, never in the ledger
 * — per-poll writes would bloat history.
 */
enum class ProcessingStage {
    STARTED,
    PROCESSING,
    EXTRACTING,
    EMBEDDING,
    INDEXING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}