package com.devassist.rag;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.common.ErrorResponse;
import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;
import com.openai.errors.OpenAIException;

@RestControllerAdvice(assignableTypes = { IndexStatusController.class, RagQueryController.class,
		SummarizationController.class })
public class RagExceptionHandler {

	@ExceptionHandler({ ProjectNotFoundException.class, DocumentNotFoundException.class })
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNotFound(RuntimeException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
		return ErrorResponse.validationFailure(ex);
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
	// cause chain, not just the directly-thrown type. ClientException is the
	// newer Google SDK exception type. OpenAIException
	// (com.openai.errors) is the equivalent root for every failure the OpenAI
	// SDK itself can throw - missing/invalid key, quota, bad request, server
	// errors, network I/O - all of its subtypes extend this one class, covering
	// OpenAI once a user switches the active provider to it via ChatProviderService.
	@ExceptionHandler({ NonTransientAiException.class, BeanCreationException.class, ApiException.class,
			ClientException.class, OpenAIException.class })
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public ErrorResponse handleAiFailure(RuntimeException ex) {
		return ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable: " + deepestMessage(ex));
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
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
