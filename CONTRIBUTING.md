# Contributing to spring-ai-rag

> 📖 English | 📖 [中文](./CONTRIBUTING-zh-CN.md)

Thank you for your interest in spring-ai-rag! We welcome contributions in any form: reporting bugs, suggesting features, improving documentation, or submitting code.

## Quick Start

### 1. Fork & Clone

```bash
# After forking the repository
git clone https://github.com/<your-username>/spring-ai-rag.git
cd spring-ai-rag
git remote add upstream https://github.com/spring-ai-rag/spring-ai-rag.git
```

### 2. Development Environment

| Dependency | Version | Notes |
|------------|---------|-------|
| JDK | 17+ | Recommended: 21 |
| Maven | 3.9+ | Build tool |
| PostgreSQL | 15+ | Requires `vector` and `pg_trgm` extensions |
| API Key | LLM provider key | OpenAI / DeepSeek / Anthropic (any one) |

### 3. Database Initialization

```sql
CREATE DATABASE spring_ai_rag;
\c spring_ai_rag
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Flyway will automatically create the schema on first startup (executes `db/migration/V1__init_rag_schema.sql`).

### 4. Environment Configuration

Create `.env` in the project root (already in `.gitignore`):

```bash
# LLM Configuration
OPENAI_API_KEY=your-key
OPENAI_BASE_URL=https://api.deepseek.com/v1
LLM_PROVIDER=openai

# Database
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=spring_ai_rag_dev
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your-password

# Embedding Model (SiliconFlow)
SILICONFLOW_API_KEY=your-key
```

### 5. Verify Setup

```bash
export $(cat .env | grep -v '^#' | xargs)
mvn clean test
```

All tests passing = environment is ready.

## Project Structure

```
spring-ai-rag/
├── spring-ai-rag-api/          # DTOs and interface definitions
├── spring-ai-rag-core/         # Core implementation (Advisor / Controller / Service)
├── spring-ai-rag-starter/      # Spring Boot auto-configuration
├── spring-ai-rag-documents/    # Document processing (chunking, cleaning)
└── demos/                      # Sample projects
```

Core package structure (`spring-ai-rag-core`):

```
com.springairag.core
├── advisor/       # QueryRewrite / HybridSearch / Rerank Advisor
├── controller/    # REST Controllers
├── config/        # Configuration classes
├── entity/        # JPA entities
├── exception/     # Exception handling
├── extension/     # Domain extension interfaces
├── filter/        # Security filters
├── memory/        # Chat memory
├── metrics/       # Monitoring metrics
├── repository/    # Spring Data JPA repositories
├── retrieval/     # Hybrid retrieval core
├── service/       # Business services
├── storage/       # Storage abstraction
└── util/          # Utilities
```

## Code Conventions

### Java Conventions

- **Java 21+ (LTS, Virtual Threads)**, no Lombok (write getters/setters/constructors by hand)
- Package: `com.springairag.*`
- Class names: PascalCase, interfaces do not use `I` prefix
- Method names: camelCase, boolean methods use `is`/`has`/`can` prefix
- Constants: UPPER_SNAKE_CASE

### Spring Conventions

- Controller layer only handles parameter validation and response construction; business logic goes in Service
- Use `@ConfigurationProperties` for configuration (prefix `rag.*`)
- Use global `@ControllerExceptionHandler` for exceptions, return unified `ErrorResponse`
- API paths always use `/api/v1/rag/` prefix

### Comment Requirements

- **Class-level Javadoc**: Every class must have one, describing its responsibility
- **Public method Javadoc**: Parameters, return value, exceptions
- **Complex logic**: Inline comments explain "why", not "what"
- No meaningless comments (e.g., `// set name`)

## Testing Requirements

> **Golden Rule: Writing production code means writing tests at the same time. Both are equally important.**

### Testing Pyramid

| Layer | Tools | Ratio | Description |
|-------|-------|-------|-------------|
| Unit Tests | JUnit 5 + Mockito | 70% | Cover normal paths and edge cases |
| Integration Tests | @SpringBootTest | 20% | Component collaboration, real database |
| E2E Tests | Shell + curl | 10% | Verify complete HTTP endpoint chains |

### Running Tests

```bash
# Full test suite
export $(cat .env | grep -v '^#' | xargs) && mvn test

# Single module
export $(cat .env | grep -v '^#' | xargs) && mvn test -pl spring-ai-rag-core

# Single test class
export $(cat .env | grep -v '^#' | xargs) && mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest

# Coverage report
mvn clean test jacoco:report
# Report location: target/site/jacoco/index.html
```

### Testing Rules

- `mvn test` must pass completely before "done"
- Test failure = incomplete; do not commit or report
- Every new Controller must have a corresponding Controller unit test
- Every new Service must have a Service unit test
- Coverage target: ≥ 90% instruction coverage

## Commit Conventions

Use [Conventional Commits](https://www.conventionalcommits.org/) format:

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Type Reference

| Type | Purpose |
|------|---------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation change |
| `refactor` | Refactoring (no behavior change) |
| `test` | Add/modify tests |
| `chore` | Build/tool change |
| `perf` | Performance optimization |

### Examples

```
feat(core): add retrieval quality evaluation service

- Implement RetrievalEvaluationService interface
- Support MRR, NDCG, Recall@K metrics
- Add 12 unit tests

Closes #42
```

### Pre-commit Checklist

- [ ] `mvn test` passes completely
- [ ] New code has corresponding tests
- [ ] Code follows project conventions (no Lombok, correct package names)
- [ ] If config changed, documentation is updated
- [ ] Commit message follows Conventional Commits format

## PR Process

1. **Create branch**: `git checkout -b feat/your-feature-name`
2. **Develop & test**: Local `mvn test` passes
3. **Stay in sync**: `git rebase upstream/main`
4. **Push**: `git push origin feat/your-feature-name`
5. **Create PR**: Fill in change description, link to Issue
6. **Code Review**: At least one maintainer approves
7. **Merge**: Squash merge to main

### PR Title

Also use Conventional Commits format: `feat(core): add XXX feature`

### PR Description Template

```markdown
## Changes
<!-- Describe what you did -->

## Change Type
- [ ] New feature
- [ ] Bug fix
- [ ] Refactor
- [ ] Documentation
- [ ] Tests

## Testing
- [ ] `mvn test` passes completely
- [ ] Added XX new tests

## Related Issue
Closes #
```

## Reporting Bugs

Submit bug reports in GitHub Issues, including:

1. **Environment info**: JDK version, OS, PostgreSQL version
2. **Reproduction steps**: Minimal reproducible steps
3. **Expected behavior**: What you expected to happen
4. **Actual behavior**: What actually happened
5. **Logs/screenshots**: Related error logs

## Feature Requests

1. Search existing Issues first to avoid duplicates
2. Explain the use case (why is this feature needed)
3. Describe the expected API or interaction
4. If possible, include alternative solutions

## Documentation Contributions

Documentation is as important as code. Feel free to submit PRs for documentation issues:

- Fix typos or broken links
- Add missing usage instructions
- Improve example code

Documentation locations:
- `README.md` — Project front page
- `docs/` — Detailed documentation
- Code Javadoc — API documentation

## Questions?

- GitHub Issues: Report bugs / request features
- Code questions: Discuss in PRs

---

Thank you for your contribution! Every Issue, PR, and Star supports the project.
