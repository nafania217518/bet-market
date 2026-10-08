package com.betting.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ApiError(val code: String, val message: String)

open class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ApiError> = ResponseEntity.status(e.status).body(ApiError(e.code, e.message ?: ""))

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class, MissingRequestHeaderException::class)
    fun validation(e: Exception): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("VALIDATION_ERROR", e.message ?: "Invalid request"))
}
