package com.devassist.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameDocumentRequest(@NotBlank @Size(max = 200) String title) {
}
