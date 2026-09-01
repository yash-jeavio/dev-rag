package com.devassist.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectServiceTest {

	private final ProjectService projectService = new ProjectService();

	@Test
	void createAssignsNonNullId() {
		Project project = projectService.create(
				new CreateProjectRequest("DevAssist", "A dev assistant", "Java", "https://github.com/example/repo"));

		assertThat(project.id()).isNotBlank();
	}

	@Test
	void createStoresCorrectFieldValues() {
		Project project = projectService.create(
				new CreateProjectRequest("DevAssist", "A dev assistant", "Java", "https://github.com/example/repo"));

		assertThat(project.name()).isEqualTo("DevAssist");
		assertThat(project.description()).isEqualTo("A dev assistant");
		assertThat(project.language()).isEqualTo("Java");
		assertThat(project.repositoryUrl()).isEqualTo("https://github.com/example/repo");
	}

	@Test
	void createAssignsDistinctIdsForEachProject() {
		Project first = projectService
				.create(new CreateProjectRequest("First", null, "Java", "https://github.com/example/first"));
		Project second = projectService
				.create(new CreateProjectRequest("Second", null, "Java", "https://github.com/example/second"));

		assertThat(first.id()).isNotEqualTo(second.id());
	}

	@Test
	void findByIdReturnsPreviouslyCreatedProject() {
		Project created = projectService
				.create(new CreateProjectRequest("DevAssist", null, "Java", "https://github.com/example/repo"));

		Project found = projectService.findById(created.id());

		assertThat(found).isEqualTo(created);
	}

	@Test
	void findByIdThrowsWhenProjectDoesNotExist() {
		assertThatThrownBy(() -> projectService.findById("unknown-id"))
				.isInstanceOf(ProjectNotFoundException.class);
	}
}
