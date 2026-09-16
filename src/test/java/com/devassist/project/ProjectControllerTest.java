package com.devassist.project;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProjectService projectService;

	@Test
	void createReturnsCreatedWithLocationAndBody() throws Exception {
		Project project = new Project("abc-123", "DevAssist", "A dev assistant", "Java",
				"https://github.com/example/repo");
		given(projectService.create(any(CreateProjectRequest.class))).willReturn(project);

		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"description": "A dev assistant",
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/projects/abc-123"))
				.andExpect(jsonPath("$.id").value("abc-123"))
				.andExpect(jsonPath("$.name").value("DevAssist"))
				.andExpect(jsonPath("$.description").value("A dev assistant"))
				.andExpect(jsonPath("$.language").value("Java"))
				.andExpect(jsonPath("$.repositoryUrl").value("https://github.com/example/repo"));
	}

	@Test
	void createReturnsBadRequestWhenNameIsMissing() throws Exception {
		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
	}

	@Test
	void createSucceedsWhenDescriptionIsOmitted() throws Exception {
		Project project = new Project("abc-123", "DevAssist", null, "Java", "https://github.com/example/repo");
		given(projectService.create(any(CreateProjectRequest.class))).willReturn(project);

		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("DevAssist"))
				.andExpect(jsonPath("$.description").value(nullValue()));
	}

	@Test
	void createReturnsBadRequestWhenNameExceedsMaxLength() throws Exception {
		String tooLongName = "a".repeat(201);

		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "%s",
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				""".formatted(tooLongName)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void createReturnsBadRequestWhenLanguageExceedsMaxLength() throws Exception {
		String tooLongLanguage = "a".repeat(51);

		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"language": "%s",
					"repositoryUrl": "https://github.com/example/repo"
				}
				""".formatted(tooLongLanguage)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("language"));
	}

	@Test
	void createReturnsBadRequestWhenDescriptionExceedsMaxLength() throws Exception {
		String tooLongDescription = "a".repeat(2001);

		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"description": "%s",
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				""".formatted(tooLongDescription)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("description"));
	}

	@Test
	void createReturnsBadRequestWhenLanguageIsBlank() throws Exception {
		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"language": "",
					"repositoryUrl": "https://github.com/example/repo"
				}
				"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createReturnsBadRequestWhenRepositoryUrlIsNotAValidUrl() throws Exception {
		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"language": "Java",
					"repositoryUrl": "not-a-url"
				}
				"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getByIdReturnsOkWhenProjectExists() throws Exception {
		Project project = new Project("abc-123", "DevAssist", "A dev assistant", "Java",
				"https://github.com/example/repo");
		given(projectService.findById(eq("abc-123"))).willReturn(project);

		mockMvc.perform(get("/api/projects/{id}", "abc-123"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("abc-123"));
	}

	@Test
	void getByIdReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
		given(projectService.findById(eq("missing"))).willThrow(new ProjectNotFoundException("missing"));

		mockMvc.perform(get("/api/projects/{id}", "missing"))
				.andExpect(status().isNotFound());
	}

	@Test
	void listsAllProjects() throws Exception {
		given(projectService.findAll()).willReturn(List.of(
				new Project("p1", "First", null, "Java", "https://example.com/1"),
				new Project("p2", "Second", null, "Go", "https://example.com/2")));

		mockMvc.perform(get("/api/projects"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("First"));
	}

	@Test
	void listingReturnsEmptyArrayWhenNoProjectsExist() throws Exception {
		given(projectService.findAll()).willReturn(List.of());

		mockMvc.perform(get("/api/projects"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void updateReturnsOkWithUpdatedBody() throws Exception {
		Project updated = new Project("abc-123", "Renamed", "Updated description", "Kotlin",
				"https://github.com/example/renamed");
		given(projectService.update(eq("abc-123"), any(UpdateProjectRequest.class))).willReturn(updated);

		mockMvc.perform(put("/api/projects/{id}", "abc-123").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "Renamed",
					"description": "Updated description",
					"language": "Kotlin",
					"repositoryUrl": "https://github.com/example/renamed"
				}
				"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("abc-123"))
				.andExpect(jsonPath("$.name").value("Renamed"))
				.andExpect(jsonPath("$.description").value("Updated description"))
				.andExpect(jsonPath("$.language").value("Kotlin"))
				.andExpect(jsonPath("$.repositoryUrl").value("https://github.com/example/renamed"));
	}

	@Test
	void updateReturnsBadRequestWhenNameIsMissing() throws Exception {
		mockMvc.perform(put("/api/projects/{id}", "abc-123").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
	}

	@Test
	void updateReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
		given(projectService.update(eq("missing"), any(UpdateProjectRequest.class)))
				.willThrow(new ProjectNotFoundException("missing"));

		mockMvc.perform(put("/api/projects/{id}", "missing").contentType(MediaType.APPLICATION_JSON).content("""
				{
					"name": "DevAssist",
					"language": "Java",
					"repositoryUrl": "https://github.com/example/repo"
				}
				"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void createReturnsBadRequestWhenBodyIsMalformedJson() throws Exception {
		mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{ not valid json"))
				.andExpect(status().isBadRequest());
	}
}
