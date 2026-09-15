package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EvalPropertiesTest {

	@Autowired
	private EvalProperties properties;

	@Test
	void bindsDefaultsFromApplicationProperties() {
		assertThat(properties.projectName()).isEqualTo("RAG Evaluation Corpus");
		assertThat(properties.datasetPath()).isEqualTo("classpath:eval/eval-dataset.json");
		assertThat(properties.minFaithfulness()).isEqualTo(4.0);
		assertThat(properties.minCompleteness()).isEqualTo(4.0);
		assertThat(properties.minCorrectlyDeclinedRate()).isEqualTo(1.0);
	}
}
