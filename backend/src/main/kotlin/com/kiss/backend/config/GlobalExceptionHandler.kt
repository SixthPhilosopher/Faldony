package com.kiss.backend.config

import com.kiss.backend.model.dto.ApiError
import com.kiss.backend.model.dto.FieldError
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNoHandlerFound(
        ex: NoHandlerFoundException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "NOT_FOUND",
            message = "Endpoint not found: ${ex.httpMethod} ${ex.requestURL}"
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(apiError)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(
        ex: NoResourceFoundException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "NOT_FOUND",
            message = "Resource not found"
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(apiError)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        ex: ResponseStatusException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val code = when (ex.statusCode) {
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            HttpStatus.CONFLICT -> "CONFLICT"
            HttpStatus.BAD_REQUEST -> "BAD_REQUEST"
            HttpStatus.UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE"
            HttpStatus.PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE"
            HttpStatus.SERVICE_UNAVAILABLE -> "EMBEDDING_UNAVAILABLE"
            else -> ex.statusCode.toString()
        }
        val message = ex.reason ?: "An error occurred"
        val apiError = ApiError(code = code, message = message)
        return ResponseEntity.status(ex.statusCode).body(apiError)
    }

    /**
     * Covers BOTH @Valid @RequestBody (MethodArgumentNotValidException, which
     * extends BindException) and @Valid @ModelAttribute binding
     * (plain BindException) — e.g. the documents list filter set.
     */
    @ExceptionHandler(BindException::class)
    fun handleBindException(
        ex: BindException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val fieldErrors = ex.bindingResult.fieldErrors.map {
            FieldError(field = it.field, message = it.defaultMessage ?: "Invalid value")
        }
        val apiError = ApiError(
            code = "VALIDATION_ERROR",
            message = "Validation failed",
            details = fieldErrors
        )
        logger.warn("Validation failed (Bind): $fieldErrors")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val fieldErrors = ex.constraintViolations.map {
            FieldError(field = it.propertyPath.toString(), message = it.message)
        }
        val apiError = ApiError(
            code = "VALIDATION_ERROR",
            message = "Validation failed",
            details = fieldErrors
        )
        logger.warn("Validation failed (ConstraintViolation): \$fieldErrors")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "BAD_REQUEST",
            message = "Invalid argument type: ${ex.name}"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSizeExceeded(
        ex: MaxUploadSizeExceededException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "PAYLOAD_TOO_LARGE",
            message = "File size exceeds maximum allowed size"
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(apiError)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "MALFORMED_REQUEST",
            message = "Request body is malformed or missing"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        ex: IllegalArgumentException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "BAD_REQUEST",
            message = ex.message ?: "Invalid argument"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(
        ex: IllegalStateException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "CONFLICT",
            message = ex.message ?: "Invalid state"
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(apiError)
    }

    /** Required multipart part missing (e.g. `file` or `metadata` on upload). */
    @ExceptionHandler(MissingServletRequestPartException::class)
    fun handleMissingPart(
        ex: MissingServletRequestPartException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        logger.warn("Missing multipart part {}", ex.requestPartName)
        val apiError = ApiError(
            code = "BAD_REQUEST",
            message = "Missing required part '${ex.requestPartName}'"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    /** Required request parameter missing. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(
        ex: MissingServletRequestParameterException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        val apiError = ApiError(
            code = "BAD_REQUEST",
            message = "Missing required parameter '${ex.parameterName}'"
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(apiError)
    }

    /** Unique-constraint violations (e.g. concurrent duplicate collection membership). */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(
        ex: org.springframework.dao.DataIntegrityViolationException,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        logger.warn("Data integrity violation: {}", ex.mostSpecificCause.message)
        val apiError = ApiError(
            code = "CONFLICT",
            message = "Resource already exists or violates a uniqueness constraint"
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(apiError)
    }

    private val logger = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * SSE/async responses that can no longer be written to (client
     * disconnected, or the response is already committed after an upstream
     * failure such as a DB outage). Nothing can be sent at this point —
     * returning an ApiError body here would try to serialize JSON into a
     * closed `text/event-stream` and fail a second time (the observed
     * "No converter for ApiError … text/event-stream" cascade). Log and let
     * the response stay as-is; the client reconnects per SSE semantics.
     */
    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun handleAsyncNotUsable(
        ex: AsyncRequestNotUsableException,
        request: WebRequest
    ) {
        logger.debug("Async response no longer usable (client gone/response failed): {}", ex.message)
    }

    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun handleAsyncTimeout(
        ex: AsyncRequestTimeoutException,
        request: WebRequest
    ) {
        logger.debug("Async request timed out: {}", ex.message)
    }

    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException::class)
    fun handleClientAbort(
        ex: org.apache.catalina.connector.ClientAbortException,
        request: WebRequest
    ) {
        logger.debug("Client aborted connection while writing: {}", ex.message)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: WebRequest
    ): ResponseEntity<ApiError> {
        logger.error("Unhandled exception: ${ex.message}", ex)
        val apiError = ApiError(
            code = "INTERNAL_ERROR",
            message = "An unexpected error occurred"
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(apiError)
    }
}