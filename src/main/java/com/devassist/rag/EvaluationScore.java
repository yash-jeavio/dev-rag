package com.devassist.rag;

public record EvaluationScore(
		Integer faithfulness,
		Integer completeness,
		Boolean correctlyDeclined,
		String reasoning,
		Method method
) {

	public enum Method {
		JUDGED,
		PROGRAMMATIC,
		UNSCORABLE
	}
}
