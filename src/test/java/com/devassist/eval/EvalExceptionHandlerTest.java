package com.devassist.eval;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class EvalExceptionHandlerTest {

	private final EvalExceptionHandler handler = new EvalExceptionHandler();

	@Test
	void mapsCorpusNotReadyToServiceUnavailableWithTheFixedMessage() {
		Map<String, Object> body = handler.handleCorpusNotReady(new EvalCorpusNotReadyException());

		assertThat(body).containsEntry("status", HttpStatus.SERVICE_UNAVAILABLE.value());
		assertThat(body).containsEntry("message",
				"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet.");
	}
}
