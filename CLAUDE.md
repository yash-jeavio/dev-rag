# CLAUDE.md

Guidance for Claude Code (and other AI assistants) working in this repository.

## 1. Project Overview

DevAssist (Maven artifact: `devassist`, base package: `com.devassist`) is a Spring Boot application in its initial scaffolding stage. It currently exposes no custom functionality — only the generated application entry point and default configuration. Treat this as a greenfield project: build features incrementally on top of standard Spring Boot conventions, and keep the codebase minimal until requirements are known.

## 2. Technology Stack

- **Language:** Java 17
- **Framework:** Spring Boot 4.1.1 (via `spring-boot-starter-parent`)
- **Build tool:** Maven, invoked through the Maven Wrapper (`mvnw` / `mvnw.cmd`) — do not require a global Maven install
- **Web layer:** Spring Web MVC (`spring-boot-starter-webmvc`)
- **Validation:** Spring Validation (`spring-boot-starter-validation`)
- **Testing:** Spring Boot Test, including the MVC test slice (`spring-boot-starter-webmvc-test`) and validation test slice (`spring-boot-starter-validation-test`), on JUnit 5 (Jupiter)
- **Packaging:** Executable jar via `spring-boot-maven-plugin`

No database, persistence, containerization, or AI/LLM integration is present yet. See [Section 9](#9-important-rules-for-ai-assisted-development).

## 3. Build and Test Commands

Always use the wrapper so the correct Maven version is used:

```bash
./mvnw clean compile        # compile the project
./mvnw test                 # run the test suite
./mvnw verify                # run tests plus any bound verification phases
./mvnw spring-boot:run       # run the application locally
./mvnw clean package         # build the executable jar (target/*.jar)
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

## 4. Current Project Structure

```
DevAssist/
├── mvnw, mvnw.cmd            # Maven Wrapper scripts
├── .mvn/wrapper/              # Maven Wrapper jar/config
├── pom.xml                    # Single-module Maven project
└── src/
    ├── main/
    │   ├── java/com/devassist/
    │   │   └── DevassistApplication.java   # @SpringBootApplication entry point
    │   └── resources/
    │       └── application.properties       # spring.application.name=devassist
    └── test/
        └── java/com/devassist/
            └── DevassistApplicationTests.java  # @SpringBootTest context-load smoke test
```

This is a single-module, single-package project today. As functionality is added, prefer organizing by feature/domain under `com.devassist.<feature>` rather than by technical layer (e.g. avoid a single global `controller`/`service`/`repository` split across the whole app) unless the project stays small enough that a layered package structure is clearer.

## 5. Coding Conventions

- Standard Java conventions: `UpperCamelCase` for classes, `lowerCamelCase` for methods/fields, `com.devassist.*` package naming.
- Follow standard Spring Boot conventions for annotations, configuration, and component structure (e.g. `@RestController`, `@Service`, `@ConfigurationProperties` where appropriate).
- Keep classes focused and small; avoid speculative abstractions or configuration for features that don't exist yet.
- Use `application.properties` for configuration unless YAML is specifically requested — don't introduce a second config format without reason.
- No comment boilerplate: only comment non-obvious "why," not "what."

## 6. Architecture Guidelines

- **Constructor injection only.** Do not use field (`@Autowired` on fields) or setter injection for beans.
- Keep controllers thin — request/response mapping and validation only; push business logic into service classes.
- Use Spring Validation (`jakarta.validation` annotations, `@Valid`/`@Validated`) for input validation at controller boundaries.
- Favor immutable request/response DTOs (records are a good fit for Java 17) over reusing entity/domain objects directly in the web layer.
- No persistence layer exists yet — do not introduce repositories, entities, or a database until explicitly requested (see Section 9).

## 7. Dependency Guidelines

- **Do not add a new dependency unless it is necessary for the specific feature being implemented.** Prefer what's already in the Spring Boot BOM/starters already on the classpath.
- Before adding a dependency, check whether Spring Boot/Spring Web/Spring Validation already provide the needed capability.
- When a new dependency truly is required, let Spring Boot's dependency management (the parent BOM) control the version — don't pin an explicit `<version>` unless the BOM doesn't cover it.
- Keep `pom.xml` changes scoped to what the current feature needs; don't bulk-add "might be useful later" starters.

## 8. Testing Guidelines

- **Write tests for new business logic** — service-level unit tests at minimum.
- Use the appropriate Spring Boot test slice for the layer under test:
  - `@WebMvcTest` (enabled by `spring-boot-starter-webmvc-test`) for controller/web-layer tests.
  - Plain JUnit 5 unit tests (no Spring context) for services and other logic that doesn't need a container.
  - `@SpringBootTest` sparingly, for true integration/context-load tests (as in `DevassistApplicationTests`).
- Use `spring-boot-starter-validation-test` utilities when testing bean validation constraints directly.
- Run `./mvnw test` before considering a change complete.
- Keep test package structure mirroring `src/main/java` (`src/test/java/com/devassist/...`).

## 9. Important Rules for AI-Assisted Development

- **Java 17 / Spring Boot 4.1.1** are fixed constraints — do not upgrade, downgrade, or suggest alternative frameworks.
- **Maven + Maven Wrapper only** — do not introduce Gradle or bypass the wrapper.
- **Do not add dependencies unless necessary** for the requested feature (see Section 7).
- **Prefer simple, maintainable solutions** over clever or heavily abstracted ones.
- **Follow standard Java and Spring conventions** rather than inventing project-specific patterns.
- **Use constructor injection** for all Spring-managed dependencies.
- **Write tests for new business logic.**
- **Do not add Anthropic API integration yet** — this is explicitly out of scope until requested.
- **Do not add RAG, PGVector, Docker, or database functionality yet** — explicitly out of scope until requested.
- **Do not modify unrelated files when implementing a feature.** Keep diffs scoped to the task at hand.
- When a request implies one of the "not yet" items above, flag the conflict and ask before implementing it rather than adding it silently.
