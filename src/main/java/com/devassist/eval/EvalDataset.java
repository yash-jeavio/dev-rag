package com.devassist.eval;

import java.io.IOException;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EvalDataset {

	private final List<EvalQuestion> questions;

	// Loaded eagerly in the constructor, at application startup, so a
	// malformed checked-in file fails the build/boot immediately - unlike
	// EvalCorpusSeeder's resilience posture, this is a deterministic local
	// file with no external dependency, so there is nothing to retry and no
	// reason to hide a bug in it.
	public EvalDataset(ResourceLoader resourceLoader, ObjectMapper objectMapper, EvalProperties properties) {
		Resource resource = resourceLoader.getResource(properties.datasetPath());
		try {
			this.questions = objectMapper.readValue(resource.getInputStream(), new TypeReference<List<EvalQuestion>>() {
			});
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not load eval dataset from " + properties.datasetPath(), ex);
		}
	}

	public List<EvalQuestion> questions() {
		return List.copyOf(questions);
	}
}
