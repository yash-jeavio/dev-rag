package com.devassist.project;

public record ProjectResponse(String id, String name, String description, String language, String repositoryUrl) {

	public static ProjectResponse from(Project project) {
		return new ProjectResponse(project.id(), project.name(), project.description(), project.language(),
				project.repositoryUrl());
	}
}
