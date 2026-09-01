package com.devassist.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateProjectRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 2000) String description,
		@NotBlank @Size(max = 50) String language,
		@NotBlank @URL String repositoryUrl) {
}
