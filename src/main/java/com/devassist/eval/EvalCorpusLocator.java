package com.devassist.eval;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.devassist.project.Project;
import com.devassist.project.ProjectService;

@Component
public class EvalCorpusLocator {

	private final ProjectService projectService;
	private final EvalProperties properties;

	public EvalCorpusLocator(ProjectService projectService, EvalProperties properties) {
		this.projectService = projectService;
		this.properties = properties;
	}

	public Optional<String> findProjectId() {
		return projectService.findAll().stream()
				.filter(project -> properties.projectName().equals(project.name()))
				.map(Project::id)
				.findFirst();
	}
}
