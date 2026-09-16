package com.devassist.common;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorResponseTest {

	@Test
	void ofBuildsAMessageOnlyResponseWithNoErrors() {
		ErrorResponse response = ErrorResponse.of(HttpStatus.NOT_FOUND, "project not found");

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.message()).isEqualTo("project not found");
		assertThat(response.errors()).isNull();
	}

	@Test
	void validationFailureBuildsAnErrorsOnlyResponseWithNoMessage() {
		FieldError fieldError = new FieldError("object", "name", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = ErrorResponse.validationFailure(ex);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.message()).isNull();
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("name", "must not be blank"));
	}
}
