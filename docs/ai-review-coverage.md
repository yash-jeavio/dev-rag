# AI Review Coverage

This document records what AI-assisted code review is effective at
catching and what still requires human review.

## AI Review Coverage

| Area | AI Can Catch | Human Review Required |
|---|---|---|
| Null handling | Yes | Verify business impact |
| Input validation | Yes | Verify requirements |
| Exception handling | Yes | Verify correct business behavior |
| Missing tests | Yes | Decide whether coverage is sufficient |
| Code style | Yes | Team-specific judgment |
| Obvious security issues | Yes | Security/domain review |
| API consistency | Yes | Product/API design decisions |
| Product intent | Limited | Yes |
| Architecture | Limited | Yes |
| Business logic | Limited | Yes |
| Team/project context | Limited | Yes |
| Knowledge sharing / mentoring | No | Yes |

## DevAssist Example

### AI Review Findings

During the review of the PUT `/api/projects/{id}` feature, AI identified:

- Missing validation-test parity for the update endpoint.
- Potential authentication considerations.
- Potential concurrency considerations.

### Human Decisions

#### Validation tests

The missing validation tests were classified as Low severity and
optional. The existing validation annotations mirror the create request,
so this was treated as a consistency improvement rather than a blocker.

**Decision:** No change required for the current feature.

#### Authentication

Authentication was not added because authentication is outside the
current DevAssist project scope.

**Decision:** Do not change.

#### Concurrency

The current in-memory implementation does not have delete operations,
and last-write-wins behavior is acceptable for the current scope.

**Decision:** Do not introduce speculative synchronization changes.

## AI Security & Responsible-Use Guardrails

### Never Provide to Claude

Do not put the following into prompts, `CLAUDE.md`, committed source
files, or other project documentation:

- API keys
- Passwords
- Certificates or private keys
- Database credentials
- Connection strings containing secrets
- Personal or sensitive data
- Patient or financial data
- Proprietary business algorithms
- Internal security architecture details

Use environment variables, secret-management systems, or other approved
secure mechanisms for sensitive configuration.

### Java/Spring Verification

AI-generated Java and Spring code must be verified against the actual
project versions and dependencies.

- Verify Spring annotations and their supported parameters.
- Verify Java APIs against the project's Java version.
- Verify Hibernate/JPQL syntax.
- Verify third-party library APIs against the installed library version.
- Verify Maven dependency coordinates before adding dependencies.
- Run the project's build and tests after implementation.

### Security Review Checklist

Before accepting AI-generated Java/Spring changes:

- [ ] Input validation is present and appropriate.
- [ ] Authentication and authorization are handled where required.
- [ ] SQL/JPQL queries are protected against injection.
- [ ] API error responses do not expose internal details or stack traces.
- [ ] Sensitive information is not written to logs.
- [ ] New dependencies are necessary and intentionally reviewed.
- [ ] Transaction boundaries are correct where transactions are used.
- [ ] AI-generated security findings have been reviewed by a developer.

### Human Responsibility

AI-assisted review is a quality-assistance step, not a replacement for
human code review.

Humans remain responsible for:

- Product intent
- Architecture
- Business logic
- Security decisions
- Team conventions
- Data-handling decisions
- Final approval

AI recommendations and findings must be evaluated by a developer before
changes are accepted.

## Review Principle

AI should improve review quality and reduce repetitive effort, while
developers remain accountable for the correctness, security, and
appropriateness of the final code.
