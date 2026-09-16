package com.devassist.rag;

import java.util.List;

import com.google.genai.errors.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.devassist.common.ErrorResponse;
import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagExceptionHandlerTest {

	private final RagExceptionHandler handler = new RagExceptionHandler();

	@Test
	void mapsProjectNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleNotFound(new ProjectNotFoundException("proj-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("proj-x");
	}

	@Test
	void mapsDocumentNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleNotFound(new DocumentNotFoundException("doc-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("doc-x");
	}

	@Test
	void mapsValidationFailureToBadRequestWithFieldErrors() {
		FieldError fieldError = new FieldError("object", "question", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = handler.handleValidation(ex);

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("question", "must not be blank"));
	}

	@Test
	void mapsAnAiProviderFailureToServiceUnavailableWithTheRootCauseMessage() {
		ApiException rootCause = new ApiException(404, "NOT_FOUND", "model retired");
		ErrorResponse response = handler.handleAiFailure(new NonTransientAiException("wrapper", rootCause));

		assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
		assertThat(response.message()).contains("model retired");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
