package com.devassist.eval;

public class EvalCorpusNotReadyException extends RuntimeException {

	public EvalCorpusNotReadyException() {
		super("Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet.");
	}
}
