package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RagPropertiesTest {

	@Autowired
	private RagProperties properties;

	@Test
	void bindsDefaultsFromApplicationProperties() {
		assertThat(properties.topK()).isEqualTo(5);
		assertThat(properties.similarityThreshold()).isEqualTo(0.5);
		assertThat(properties.chunkSizeChars()).isEqualTo(2000);
		assertThat(properties.chunkOverlapChars()).isEqualTo(200);
	}
}
