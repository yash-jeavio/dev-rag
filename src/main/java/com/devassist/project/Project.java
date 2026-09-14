package com.devassist.project;

public record Project(
        String id,
        String name,
        String description,
        String language,
        String repositoryUrl
) {
}
