package com.devassist.project;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import com.devassist.common.AbstractErrorResponseExceptionHandler;
import com.devassist.common.ErrorResponse;

@RestControllerAdvice(assignableTypes = ProjectController.class)
public class ProjectExceptionHandler extends AbstractErrorResponseExceptionHandler {

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
	public ErrorResponse handleNotFound(ProjectNotFoundException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}
}
