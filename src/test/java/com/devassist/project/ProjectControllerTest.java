package com.devassist.project;

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
}
