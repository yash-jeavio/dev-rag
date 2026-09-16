package com.devassist.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;

// @JsonInclude(NON_NULL) keeps the JSON shape byte-identical to every
// existing Map.of(...)-based handler response: a message-only response
// never serializes "errors": null, and vice versa (BR-06,
// specs/observability-and-deployment.md).
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String message, List<FieldError> errors) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(HttpStatus status, String message) {
		return new ErrorResponse(status.value(), message, null);
	}

	public static ErrorResponse validationFailure(MethodArgumentNotValidException ex) {
		List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
				.toList();
		return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), null, errors);
	}
}
