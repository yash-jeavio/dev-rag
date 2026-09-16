package com.devassist.rag;

import java.util.List;
import java.util.Map;

import com.google.genai.errors.ApiException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;

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
	// ApiException covers Google's own SDK rejecting a request (bad/retired
	// model name, quota, auth) - Spring AI does not normalise this into
	// NonTransientAiException, and it arrives wrapped in a generic RuntimeException,
	// which @ExceptionHandler still matches because Spring searches the whole
	// cause chain, not just the directly-thrown type.
	@ExceptionHandler({ NonTransientAiException.class, BeanCreationException.class, ApiException.class })
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public Map<String, Object> handleAiFailure(RuntimeException ex) {
		return Map.of("status", HttpStatus.SERVICE_UNAVAILABLE.value(),
				"message", "AI provider unavailable: " + deepestMessage(ex));
	}

	// Spring passes the exception instance that was actually thrown, which may
	// be a generic wrapper rather than the specific cause that matched this
	// handler - walk to the root cause so the caller sees Google's actual
	// explanation (e.g. "model no longer available") instead of a generic
	// wrapper message like "Failed to generate content".
	private String deepestMessage(Throwable ex) {
		Throwable current = ex;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage();
	}
}
