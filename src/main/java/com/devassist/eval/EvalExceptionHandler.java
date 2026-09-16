package com.devassist.eval;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.common.ErrorResponse;

// Scoped to EvalController only, and deliberately separate from
// com.devassist.rag.RagExceptionHandler: that class lives in the `rag`
// package, and adding a handler for an `eval`-package exception there
// would make `rag` depend on `eval` - backwards, per this feature's own
// one-way dependency rule (eval depends on rag, never the reverse).
@RestControllerAdvice(assignableTypes = EvalController.class)
public class EvalExceptionHandler {

	@ExceptionHandler(EvalCorpusNotReadyException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public ErrorResponse handleCorpusNotReady(EvalCorpusNotReadyException ex) {
		return ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}
}
