package com.devassist.rag;

import java.util.List;
import java.util.Map;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;
import com.openai.errors.OpenAIException;

@RestControllerAdvice(assignableTypes = { IndexStatusController.class, RagQueryController.class,
		SummarizationController.class })
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
	// and is not permanent, so it is 503 rather than 500. NonTransientAiException
	// covers a live call that fails (e.g. quota/auth rejected by the provider);
	// BeanCreationException covers the chat model bean itself never getting
	// built in the first place (e.g. GEMINI_API_KEY missing) - GenerationService
	// and SummaryGenerator resolve it lazily via ObjectProvider, so that failure
	// now surfaces here at call time instead of at application startup.
	// OpenAIException (com.openai.errors) is the root of every failure the
	// OpenAI SDK itself can throw - missing/invalid key, quota, bad request,
	// server errors, network I/O - all of its subtypes extend this one class.
	// This covers OpenAI once a user switches the active provider to it via
	// ChatProviderService.
	@ExceptionHandler({ NonTransientAiException.class, BeanCreationException.class, OpenAIException.class })
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public Map<String, Object> handleAiFailure(RuntimeException ex) {
		return Map.of("status", HttpStatus.SERVICE_UNAVAILABLE.value(),
				"message", "AI provider unavailable: " + ex.getMessage());
	}
}
