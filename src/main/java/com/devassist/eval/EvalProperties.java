package com.devassist.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devassist.eval")
public record EvalProperties(
		String projectName,
		String datasetPath,
		double minFaithfulness,
		double minCompleteness,
		double minCorrectlyDeclinedRate
) {
}
