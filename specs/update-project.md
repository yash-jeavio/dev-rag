# Feature Specification: `PUT /api/projects/{id}`

## 1. Goal

Allow a client to fully replace the mutable fields of an existing project identified by its `id`, using standard REST PUT semantics.

## 2. Interface

PUT /api/projects/{id}

Content-Type: application/json
Accept: application/json

Controller method:

ProjectController.update(String id, UpdateProjectRequest request)

The request body is validated using `@Valid`.

The endpoint returns `ResponseEntity<ProjectResponse>` with HTTP 200 OK.

## 3. Inputs

Path parameter:

- `id` — String, required, identifying an existing project.

Request body:

`UpdateProjectRequest`

Fields:

- `name` — required, not blank, maximum 200 characters.
- `description` — optional, maximum 2000 characters.
- `language` — required, not blank, maximum 50 characters.
- `repositoryUrl` — required, not blank, valid URL.

The request body must not contain an `id`. The path variable is the source of identity.

## 4. Outputs

On success:

- HTTP 200 OK
- Response body is the existing `ProjectResponse`.
- The project's existing ID is preserved.
- No `Location` header is required.

## 5. Business Rules

- PUT performs full replacement of the project's mutable fields.
- The project ID is immutable.
- The ID comes only from the path variable.
- The target project must already exist.
- An unknown ID must not create a new project.
- The updated project replaces the existing entry in the in-memory `ConcurrentHashMap`.
- No persistence, database, ETag, or optimistic locking changes are required.

## 6. Error Conditions

### Project not found

- HTTP 404 Not Found
- Reuse the existing `ProjectNotFoundException`.
- Reuse the existing not-found exception handler.

### Validation failure

- HTTP 400 Bad Request.
- Reuse the existing validation handling.
- Validation applies to name, description, language, and repositoryUrl.

### Malformed JSON

- HTTP 400 Bad Request.
- Existing/default Spring handling is acceptable.

### Unsupported media type

- HTTP 415 Unsupported Media Type.
- Existing/default Spring MVC handling is acceptable.

No new exception types are required.

## 7. Non-Functional Requirements

- Continue using the existing thread-safe `ConcurrentHashMap`.
- Do not introduce a database or persistence layer.
- Do not add new dependencies.
- Keep the controller thin.
- Use constructor injection.
- Use an immutable record for `UpdateProjectRequest`.
- Add service tests for successful update and unknown ID.
- Add controller tests for successful update, validation failure, and not-found behavior.
- Run the full Maven test suite after implementation.
