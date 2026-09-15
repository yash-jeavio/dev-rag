package com.devassist.eval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.devassist.rag.ChatProviderService;

@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfiguration {

	// JudgeService and EvalDataset are wired here (rather than component-scanned
	// via @Service/@Component) so each gets its own PRIVATE ObjectMapper instance
	// that is never itself registered as a Spring bean. Publishing an ObjectMapper
	// bean would satisfy Spring Boot's @ConditionalOnBean(ObjectMapper.class) gate
	// on MappingJackson2HttpMessageConverterConfiguration, risking an app-wide
	// switch to a bare, uncustomized ObjectMapper (no JavaTimeModule) for all HTTP
	// JSON serialization if the preferred-json-mapper condition ever changes.
	@Bean
	public JudgeService judgeService(ChatProviderService chatProviderService) {
		return new JudgeService(chatProviderService, new ObjectMapper());
	}

	@Bean
	public EvalDataset evalDataset(ResourceLoader resourceLoader, EvalProperties properties) {
		return new EvalDataset(resourceLoader, new ObjectMapper(), properties);
	}

}
