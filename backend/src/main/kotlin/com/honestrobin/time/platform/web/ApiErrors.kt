// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.web

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Error body for every non-2xx API response. `code` is stable and machine-readable; `message` is for humans. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ApiError(
    val code: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val details: Map<String, Any?> = emptyMap(),
)

open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val fields: Map<String, String> = emptyMap(),
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

class NotFoundException(entity: String) : ApiException(HttpStatus.NOT_FOUND, "not_found", "$entity not found")

class ForbiddenException(message: String = "You don't have permission to do this") :
    ApiException(HttpStatus.FORBIDDEN, "forbidden", message)

class ConflictException(code: String, message: String, details: Map<String, Any?> = emptyMap()) :
    ApiException(HttpStatus.CONFLICT, code, message, details = details)

class ValidationException(fields: Map<String, String>, message: String = "Please check the highlighted fields") :
    ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", message, fields) {
    constructor(field: String, error: String) : this(mapOf(field to error), error)
}

class BadRequestException(code: String, message: String) : ApiException(HttpStatus.BAD_REQUEST, code, message)

class PreconditionFailedException :
    ApiException(HttpStatus.PRECONDITION_FAILED, "stale", "This record was changed by someone else. Reload and try again.")

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(ApiError(e.code, e.message, e.fields, e.details))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val fields = e.bindingResult.fieldErrors.associate { snake(it.field) to (it.defaultMessage ?: "invalid") }
        return ResponseEntity.unprocessableEntity().body(ApiError("validation_failed", "Please check the highlighted fields", fields))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun unreadable(e: Exception): ResponseEntity<ApiError> {
        generateSequence(e as Throwable) { it.cause }.filterIsInstance<RequestTooLargeException>().firstOrNull()?.let {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(RequestSizeLimitFilter.tooLarge(it.limit))
        }
        return ResponseEntity.badRequest().body(ApiError("bad_request", e.message?.substringBefore(":") ?: "Malformed request"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(e: AccessDeniedException) = ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError("forbidden", "You don't have permission to do this"))

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun stale(e: OptimisticLockingFailureException) = api(PreconditionFailedException())

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError("method_not_allowed", e.message ?: "Method not allowed"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(e: NoResourceFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError("not_found", "Not found"))

    /** The browser went away (a closed tab, a live-update stream ending): nothing to answer, nothing wrong. */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException::class)
    fun clientGone(e: Exception) {
        log.debug("Client went away: {}", e.message)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled error", e)
        return ResponseEntity.internalServerError().body(ApiError("internal_error", "Something went wrong on our side"))
    }

    private fun snake(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}
