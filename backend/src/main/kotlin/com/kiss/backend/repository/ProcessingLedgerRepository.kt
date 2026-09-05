package com.kiss.backend.repository

import com.kiss.backend.model.entity.ProcessingErrorCode
import com.kiss.backend.model.entity.ProcessingLedger
import com.kiss.backend.model.entity.ProcessingStage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ProcessingLedgerRepository : JpaRepository<ProcessingLedger, Long> {
    fun findByUploadId(uploadId: String): ProcessingLedger?

    fun findByStageAndUpdatedAtBefore(stage: ProcessingStage, before: Instant): List<ProcessingLedger>

    fun findByStageInAndUpdatedAtBefore(stages: List<ProcessingStage>, before: Instant): List<ProcessingLedger>

    fun findByStageAndErrorCodeIn(stage: ProcessingStage, codes: List<ProcessingErrorCode>): List<ProcessingLedger>

    fun deleteByStageInAndUpdatedAtBefore(stages: List<ProcessingStage>, before: Instant): Long
}