package com.devassist.document;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.devassist.project.ProjectNotFoundException;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> Map.of("field", fieldError.getField(), "message", fieldError.getDefaultMessage()))
				.toList();
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "errors", errors);
	}

	@ExceptionHandler(ProjectNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, Object> handleProjectNotFound(ProjectNotFoundException ex) {
		return Map.of("status", HttpStatus.NOT_FOUND.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(DocumentNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, Object> handleDocumentNotFound(DocumentNotFoundException ex) {
		return Map.of("status", HttpStatus.NOT_FOUND.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(InvalidDocumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleInvalidDocument(InvalidDocumentException ex) {
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "message",
				"Uploaded file exceeds the maximum allowed size");
	}
}
