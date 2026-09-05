package com.kiss.backend.temporal.workflow

/**
 * Permanent, unrecoverable document-processing failure.
 * Its simple name is whitelisted in the activities' RetryOptions
 * nonRetryableErrorTypes so Temporal fails fast instead of retrying.
 */
class NonRetryableDocumentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)