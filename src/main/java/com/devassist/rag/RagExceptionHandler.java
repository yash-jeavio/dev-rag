package com.devassist.rag;

import java.util.List;
import java.util.Map;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;

@RestControllerAdvice(assignableTypes = { IndexStatusController.class, RagQueryController.class })
public class RagExceptionHandler {

	@ExceptionHandler({ ProjectNotFoundException.class, DocumentNotFoundException.class })
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, Object> handleNotFound(RuntimeException ex) {
		return Map.of("status", HttpStatus.NOT_FOUND.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> Map.of("field", fieldError.getField(), "message", fieldError.getDefaultMessage()))
				.toList();
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "errors", errors);
	}

	// A model or embedding provider being unreachable is not the caller's fault
	// and is not permanent, so it is 503 rather than 500.
	@ExceptionHandler(NonTransientAiException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public Map<String, Object> handleAiFailure(RuntimeException ex) {
		return Map.of("status", HttpStatus.SERVICE_UNAVAILABLE.value(),
				"message", "AI provider unavailable: " + ex.getMessage());
	}
}
