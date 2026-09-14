package com.devassist.rag;

import jakarta.validation.constraints.Size;

public record SummarizeRequest(@Size(max = 500) String instruction) {
}
