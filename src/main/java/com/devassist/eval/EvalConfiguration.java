package com.devassist.eval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfiguration {

	@Bean
	public ObjectMapper objectMapper() {
		return JsonMapper.builder().addModule(new JavaTimeModule()).build();
	}

	@Bean
	public EvalDataset evalDataset(ResourceLoader resourceLoader, ObjectMapper objectMapper,
			EvalProperties properties) {
		return new EvalDataset(resourceLoader, objectMapper, properties);
	}
}

