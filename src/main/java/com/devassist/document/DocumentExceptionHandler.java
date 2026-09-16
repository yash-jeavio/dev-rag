package com.devassist.document;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.devassist.common.AbstractErrorResponseExceptionHandler;
import com.devassist.common.ErrorResponse;
import com.devassist.project.ProjectNotFoundException;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler extends AbstractErrorResponseExceptionHandler {

	// No longer @ExceptionHandler-annotated directly: ResponseEntityExceptionHandler's
	// own inherited handleException(...) already claims MethodArgumentNotValidException,
	// and Spring throws an ambiguous-mapping error if a second method also declares
	// the identical type. handleMethodArgumentNotValid below is the actual override
	// point Spring dispatches to; this method stays a plain helper so its existing
	// direct-unit-test coverage needs no change.
	public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
		return ErrorResponse.validationFailure(ex);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return ResponseEntity.status(status).headers(headers).body(handleValidation(ex));
	}

	@ExceptionHandler(ProjectNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleProjectNotFound(ProjectNotFoundException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(DocumentNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleDocumentNotFound(DocumentNotFoundException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(InvalidDocumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidDocument(InvalidDocumentException ex) {
		return ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	// Same pattern as handleValidation above: kept as a plain helper so its
	// existing test coverage needs no change; handleMaxUploadSizeExceededException
	// below is the actual Spring-dispatched override (same conflict reason as
	// MethodArgumentNotValidException above).
	public ErrorResponse handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
		return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Uploaded file exceeds the maximum allowed size");
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(handleMaxUploadSizeExceeded(ex));
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}
}
