package com.devassist.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Spring's own ResponseEntityExceptionHandler already maps a whole family of
// framework-level failures (malformed JSON, wrong content type, missing
// multipart parts, unsupported HTTP methods, etc.) to the correct 4xx status
// via its inherited, final handleException(...) method - extending it here
// keeps that mapping intact. Without this, a bare @ExceptionHandler(Exception.class)
// catch-all would win over Spring's own more specific handling for these
// cases (a direct type match on Exception beats falling back to a superclass's
// own handlers), silently turning client errors into 500s. Only
// handleExceptionInternal is overridden here, to render the body in this
// project's ErrorResponse shape instead of Spring's default; subclasses that
// need to customize a specific framework exception's response override that
// exception's own protected hook (e.g. handleMethodArgumentNotValid) rather
// than declaring a second @ExceptionHandler for a type this class already
// claims - declaring two methods for the identical type throws
// IllegalStateException at context-startup time (Spring has no local-beats-
// inherited exception for that case, only for choosing among different types).
public abstract class AbstractErrorResponseExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		String message = (ex.getMessage() != null) ? ex.getMessage() : "Malformed request";
		ErrorResponse errorResponse = ErrorResponse.of(HttpStatus.valueOf(statusCode.value()), message);
		return ResponseEntity.status(statusCode).headers(headers).body(errorResponse);
	}
}
