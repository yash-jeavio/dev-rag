package com.devassist.project;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.devassist.common.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectExceptionHandlerTest {

	private final ProjectExceptionHandler handler = new ProjectExceptionHandler();

	@Test
	void mapsValidationFailureToBadRequestWithFieldErrors() {
		FieldError fieldError = new FieldError("object", "name", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = handler.handleValidation(ex);

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("name", "must not be blank"));
	}

	@Test
	void mapsProjectNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleNotFound(new ProjectNotFoundException("proj-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("proj-x");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
