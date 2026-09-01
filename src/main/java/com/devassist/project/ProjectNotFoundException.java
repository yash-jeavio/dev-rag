package com.devassist.project;

public class ProjectNotFoundException extends RuntimeException {

	public ProjectNotFoundException(String id) {
		super("Project not found: " + id);
	}
}
