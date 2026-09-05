package com.kiss.backend.model.entity

/**
 * Failure taxonomy shared by the workflow's terminal handling, the ledger and
 * the reconciler. The reconciler AUTO-RETRIES only *transient* codes
 * (DOCLING_DOWN, DOCLING_FAILED, TIMEOUT, WORKER, EMBEDDING_UNAVAILABLE, UNKNOWN);
 * *permanent* codes (BAD_FILE, CONFIG) and CANCELLED are left for the user's
 * explicit `POST /processes/{id}/retry`.
 */
enum class ProcessingErrorCode {
    BAD_FILE,
    CONFIG,
    DOCLING_DOWN,
    DOCLING_FAILED,
    EMBEDDING_UNAVAILABLE,
    TIMEOUT,
    WORKER,
    CANCELLED,
    UNKNOWN;

    val transient: Boolean
        get() = this == DOCLING_DOWN || this == DOCLING_FAILED ||
            this == TIMEOUT || this == WORKER ||
            this == EMBEDDING_UNAVAILABLE || this == UNKNOWN
}