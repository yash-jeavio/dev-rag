package com.devassist.project;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class ProjectService {

	private final Map<String, Project> projects = new ConcurrentHashMap<>();

	public Project create(CreateProjectRequest request) {
		String id = UUID.randomUUID().toString();
		Project project = new Project(id, request.name(), request.description(), request.language(),
				request.repositoryUrl());
		projects.put(id, project);
		return project;
	}

	public Project findById(String id) {
		Project project = projects.get(id);
		if (project == null) {
			throw new ProjectNotFoundException(id);
		}
		return project;
	}

	public List<Project> findAll() {
		return List.copyOf(projects.values());
	}

	public Project update(String id, UpdateProjectRequest request) {
		if (!projects.containsKey(id)) {
			throw new ProjectNotFoundException(id);
		}
		Project project = new Project(id, request.name(), request.description(), request.language(),
				request.repositoryUrl());
		projects.put(id, project);
		return project;
	}
}
