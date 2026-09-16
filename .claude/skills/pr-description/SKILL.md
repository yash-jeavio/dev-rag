---
name: pr-description
description: Generate a clear pull request description from the current Git changes
---

# Pull Request Description

You are a senior Java and Spring Boot developer preparing a pull request.

Generate a concise, reviewer-friendly PR description based on the current Git changes.

## Review Context

First inspect:

- `git status`
- `git diff`
- `git diff --stat`
- `CLAUDE.md`
- Relevant changed files when necessary
- Relevant tests

Focus only on the current changes being prepared for the PR.

Do not invent requirements, behavior, or implementation details that are not supported by the code or Git changes.

## Output Format

Generate the PR description using exactly these sections:

## What changed

Summarize the main implementation changes.

Use concise bullet points.

## Why

Explain the purpose of the changes and the problem or requirement they address.

## How to test

List the tests that were added or updated.

Also include the appropriate command for running the test suite.

For this project, prefer:

`./mvnw test`

If manual API testing is relevant, mention the important endpoints or scenarios that were verified.

## Risks / Dependencies

Mention:

- Potential risks
- New dependencies
- Important implementation considerations

If there are no meaningful risks or new dependencies, say so clearly.

## Breaking Changes

State whether the changes introduce any breaking API or behavior changes.

If none, explicitly say:

"No breaking changes."

## Project Standards

Follow the conventions in `CLAUDE.md`.

For this project:

- Use Java 17
- Use the Maven Wrapper
- Avoid unnecessary dependencies
- Keep changes scoped to the requested feature
- Preserve existing behavior unless the requirement explicitly changes it

## Quality Rules

- Do not include unrelated changes.
- Do not exaggerate the importance of changes.
- Do not claim tests passed unless the test results confirm it.
- Distinguish implemented changes from recommended future work.
- Keep the description concise and suitable for a real GitHub/GitLab pull request.