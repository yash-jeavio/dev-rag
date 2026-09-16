package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.devassist.common.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;

class EvalExceptionHandlerTest {

	private final EvalExceptionHandler handler = new EvalExceptionHandler();

	@Test
	void mapsCorpusNotReadyToServiceUnavailableWithTheFixedMessage() {
		ErrorResponse response = handler.handleCorpusNotReady(new EvalCorpusNotReadyException());

		assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
		assertThat(response.message()).isEqualTo(
				"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet.");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
