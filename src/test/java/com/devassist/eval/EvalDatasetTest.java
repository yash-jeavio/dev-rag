package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDatasetTest {

	private final EvalProperties properties = new EvalProperties("RAG Evaluation Corpus",
			"classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);

	@Test
	void loadsTheCheckedInDatasetFile() {
		EvalDataset dataset = new EvalDataset(new DefaultResourceLoader(), new ObjectMapper(), properties);

		assertThat(dataset.questions()).hasSize(8);
		assertThat(dataset.questions()).filteredOn(EvalQuestion::answerable).hasSize(6);
		assertThat(dataset.questions()).filteredOn(q -> !q.answerable()).hasSize(2);
		assertThat(dataset.questions().get(0).question())
				.isEqualTo("What is the return window for physical products?");
	}

	@Test
	void failsFastOnAMissingDatasetFile() {
		EvalProperties badPath = new EvalProperties("RAG Evaluation Corpus",
				"classpath:eval/does-not-exist.json", 4.0, 4.0, 1.0);

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new EvalDataset(new DefaultResourceLoader(), new ObjectMapper(), badPath))
				.isInstanceOf(IllegalStateException.class);
	}
}
