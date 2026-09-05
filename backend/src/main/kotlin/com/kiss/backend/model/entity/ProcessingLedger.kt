package com.kiss.backend.model.entity

import jakarta.persistence.*

/**
 * Durable record of one upload's processing lifecycle, written by the single
 * `updateProcessingStatus` activity. An upload_id maps 1:1 to a `doc-<hash>`
 * workflow and, after success, to a `document` row via [documentId].
 *
 * [stage]/[errorCode] are TYPED (the enums the DTOs/SSE/reconciler share);
 * transient failures are auto-retried by the reconciler, permanent ones only
 * via the user's explicit retry. See V1__init.sql for the schema rationale.
 */
@Entity
@Table(
    name = "processing_ledger",
    uniqueConstraints = [UniqueConstraint(columnNames = ["upload_id"], name = "uk_processing_upload_id")]
)
class ProcessingLedger(
    @Column(name = "upload_id", nullable = false, length = 64)
    var uploadId: String,

    @Column(nullable = false)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: DocumentType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var stage: ProcessingStage,

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", length = 32)
    var errorCode: ProcessingErrorCode? = null,

    @Column(length = 2000)
    var error: String? = null,

    @Column(name = "document_id")
    var documentId: Long? = null,

    /** Full DocumentProcessRequest the workflow started with (audit + retry). */
    @Column(name = "job_spec", columnDefinition = "text")
    var jobSpec: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0
) : BaseEntity()