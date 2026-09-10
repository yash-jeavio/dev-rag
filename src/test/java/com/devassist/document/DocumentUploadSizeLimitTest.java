package com.devassist.document;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.devassist.project.CreateProjectRequest;
import com.devassist.project.ProjectResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DocumentUploadSizeLimitTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void uploadOverTenMegabytesReturnsBadRequestWithSpecMessage() {
		String projectId = createProject();

		byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(tooLarge) {
			@Override
			public String getFilename() {
				return "big.txt";
			}
		});

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/projects/{projectId}/documents", request, String.class, projectId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("Uploaded file exceeds the maximum allowed size");
	}

	private String createProject() {
		ResponseEntity<ProjectResponse> response = restTemplate.postForEntity("/api/projects",
				new CreateProjectRequest("Test Project", null, "Java", "https://github.com/example/repo"),
				ProjectResponse.class);
		return response.getBody().id();
	}
}
