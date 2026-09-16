package com.devassist.rag;

import jakarta.validation.constraints.NotNull;

public record ChatProviderRequest(@NotNull ChatProvider provider) {
}
