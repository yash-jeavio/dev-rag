package com.devassist.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IngestTextRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank String content) {
}
