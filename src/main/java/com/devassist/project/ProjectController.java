package com.devassist.project;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

	private final ProjectService projectService;

	public ProjectController(ProjectService projectService) {
		this.projectService = projectService;
	}

	@PostMapping
	public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
		Project project = projectService.create(request);
		return ResponseEntity.created(URI.create("/api/projects/" + project.id())).body(ProjectResponse.from(project));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ProjectResponse> getById(@PathVariable String id) {
		Project project = projectService.findById(id);
		return ResponseEntity.ok(ProjectResponse.from(project));
	}

	@GetMapping
	public ResponseEntity<List<ProjectResponse>> list() {
		return ResponseEntity.ok(projectService.findAll().stream().map(ProjectResponse::from).toList());
	}

	@PutMapping("/{id}")
	public ResponseEntity<ProjectResponse> update(@PathVariable String id,
			@Valid @RequestBody UpdateProjectRequest request) {
		Project project = projectService.update(id, request);
		return ResponseEntity.ok(ProjectResponse.from(project));
	}
}
