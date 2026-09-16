package com.devassist.document;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.devassist.common.ErrorResponse;
import com.devassist.project.ProjectNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentExceptionHandlerTest {

	private final DocumentExceptionHandler handler = new DocumentExceptionHandler();

	@Test
	void mapsValidationFailureToBadRequestWithFieldErrors() {
		FieldError fieldError = new FieldError("object", "title", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = handler.handleValidation(ex);

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("title", "must not be blank"));
	}

	@Test
	void mapsProjectNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleProjectNotFound(new ProjectNotFoundException("proj-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("proj-x");
	}

	@Test
	void mapsDocumentNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleDocumentNotFound(new DocumentNotFoundException("doc-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("doc-x");
	}

	@Test
	void mapsInvalidDocumentToBadRequestWithTheExceptionMessage() {
		ErrorResponse response = handler.handleInvalidDocument(new InvalidDocumentException("empty file"));

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.message()).isEqualTo("empty file");
	}

	@Test
	void mapsMaxUploadSizeExceededToBadRequestWithAFixedMessage() {
		ErrorResponse response = handler
				.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(10_000_000L));

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.message()).isEqualTo("Uploaded file exceeds the maximum allowed size");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
