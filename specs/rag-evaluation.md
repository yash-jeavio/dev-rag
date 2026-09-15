# Feature Specification: RAG Evaluation Harness (Day 14)

## 1. Goal

Give the RAG system a repeatable way to measure answer quality after a
change — chunk size, similarity threshold, prompt wording — instead of
eyeballing a handful of manual queries. A fixed question set runs through
the real query pipeline and is scored automatically, producing a report
that flags regressions at a glance.

This finally implements the `evaluation` field on `RagAnswerResponse`,
reserved since [specs/rag-core.md](rag-core.md) §5.7 specifically for this
purpose.

Out of scope: RAGAS-style retrieval precision/recall (they require a
ground-truth relevant-chunk list per question, which is a heavier curation
effort than this pass justifies); a UI for the eval report (raw JSON is
enough for now); per-stage latency/token observability (that is Day 15).

## 2. Why an LLM-as-Judge, and Why It Splits in Two

No RAGAS or equivalent library exists on the JVM, and this project is
Java-only by constraint (see root `CLAUDE.md`). The two qualitative
dimensions RAGAS itself cannot capture — faithfulness and completeness —
are judged by asking Gemini directly, per the original training
curriculum's own fallback for exactly this gap.

Judging splits into two mechanisms depending on the query's outcome,
because a single uniform judge call does not make sense across both:

- **`ANSWERED`** — there is a real answer and real retrieved context, so
  Gemini is asked to judge faithfulness (does the answer stay within the
  context?) and completeness (does it address the whole question?).
- **`INSUFFICIENT_CONTEXT`** — the answer is always the same fixed refusal
  phrase with no context behind it. Asking an LLM to judge "faithfulness"
  of a canned phrase against nothing is meaningless. Instead, whether the
  refusal was *appropriate* is checked **programmatically**: did the
  question's `answerable` flag in the dataset agree with the actual
  status? This is more reliable than an LLM opinion, and free.

Consequently the aggregate report has two independent tracks, not one
blended average — see §7.

## 3. New Components — `com.devassist.eval`

Dependency direction is one-way: `eval` depends on `rag`, `document`, and
`project`, never the reverse. Nothing in those packages is modified except
`RagAnswerResponse` (§4).

| Component | Responsibility |
|---|---|
| `EvalCorpusSeeder` | `ApplicationRunner` — on startup, ensures the dedicated eval project and its documents exist |
| `EvalDataset`, `EvalQuestion` | Loaded from a classpath JSON resource at startup |
| `EvaluationScore` | The concrete type finally given to `RagAnswerResponse.evaluation` |
| `JudgeService` | Calls Gemini with a judging prompt; defensively parses the response into an `EvaluationScore` |
| `EvaluationService` | Orchestrates one full run: iterate the dataset, call the real query pipeline, judge or programmatically check each result, aggregate |
| `EvalReportResponse`, `EvalResultEntry`, `EvalSummary` | Response DTOs |
| `EvalController` | `POST /api/eval/run` |
| `EvalCorpusNotReadyException` | Thrown by `EvaluationService` when the eval project has zero indexed documents (BR-06) — distinct from `BeanCreationException`/`ApiException` in `RagExceptionHandler`, which mean an AI provider failure, not "corpus never seeded" |
| `EvalProperties` | `@ConfigurationProperties("devassist.eval")` |

## 4. Change to an Existing Type

`RagAnswerResponse.evaluation` changes from `Object` (always `null`) to
`EvaluationScore` (nullable — still `null` for every ordinary `/query` call
outside of an eval run; only populated when `EvaluationService` sets it).
This is the only change to the `rag` package.

```java
public record EvaluationScore(
        Integer faithfulness,        // 1-5, null when not applicable
        Integer completeness,        // 1-5, null when not applicable
        Boolean correctlyDeclined,   // null unless this was a deliberately-unanswerable question
        String reasoning,
        Method method                // JUDGED | PROGRAMMATIC | UNSCORABLE
) {
    public enum Method { JUDGED, PROGRAMMATIC, UNSCORABLE }
}
```

`UNSCORABLE` means an attempt was made and failed (judge call errored, or
its response could not be parsed) — distinct from a `null` score, which
would look identical to "never tried."

## 5. Startup Corpus Seeding

`EvalCorpusSeeder` runs once per application startup:

1. Look up a project named per `devassist.eval.project-name` (default
   `"RAG Evaluation Corpus"`). If it does not exist, create it.
2. If that project has no documents yet, ingest two small fixed text
   documents (checked into `src/main/resources/eval/`) via the existing
   `DocumentService.ingestText` path — the same ingestion, chunking, and
   embedding flow any real document goes through.
3. **Must never block or fail application startup.** If Ollama is
   unreachable, this step logs a warning and returns; the eval project
   simply has no indexed documents yet. This mirrors BR-11's principle
   (indexing failures do not fail the caller) applied to the one thing
   that happens automatically rather than in response to a request.

Re-running the app is idempotent: step 2's existing-documents check (backed
by [rag-core.md](rag-core.md)'s content-hash dedup, its BR-03) means
documents are not re-ingested on every restart.

If the dataset JSON file itself (§9, `dataset-path`) is malformed, that is
a deterministic, local, developer-caused error — unlike Ollama being
unreachable, there is nothing to retry and no reason to hide it. Loading
the dataset file therefore fails application startup normally, rather than
being swallowed the way corpus seeding is.

## 6. API

### 6.1 `POST /api/eval/run`

No request body. Runs the entire fixed dataset against the eval corpus.

**Precondition check**: if the eval project has zero indexed documents
(seeding never completed, most likely because Ollama was down at
startup), respond `503` immediately — see §8 — rather than run a
dataset with a guaranteed-empty corpus and report misleading results.

Response (`EvalReportResponse`), HTTP `200`:

```json
{
  "results": [
    {
      "question": "How long is the return window?",
      "expectedAnswerable": true,
      "response": {
        "question": "How long is the return window?",
        "answer": "30 days. [1]",
        "status": "ANSWERED",
        "sources": [ { "documentId": "...", "title": "product-policy.txt", "chunkIndex": 0, "similarity": 0.81, "cited": true, "excerpt": "..." } ],
        "latencyMs": 1180,
        "evaluation": {
          "faithfulness": 5, "completeness": 5, "correctlyDeclined": null,
          "reasoning": "The answer states the 30-day window exactly as given in the cited source.",
          "method": "JUDGED"
        }
      }
    }
  ],
  "summary": {
    "totalQuestions": 10,
    "answerableQuestions": 7,
    "unanswerableQuestions": 3,
    "averageFaithfulness": 4.6,
    "averageCompleteness": 4.4,
    "correctlyDeclinedRate": 1.0,
    "failedQuestions": 0,
    "passed": true
  }
}
```

`averageFaithfulness`/`averageCompleteness` are computed only over
`ANSWERED` questions whose judge call succeeded (`method: JUDGED`).
`correctlyDeclinedRate` is computed only over questions where
`expectedAnswerable: false`. A question whose `method` is `UNSCORABLE`, or
whose underlying `RagQueryService.answer()` call itself threw, is excluded
from both averages and counted in `failedQuestions` instead.

## 7. Business Rules

- **BR-01**: Startup corpus seeding never blocks or fails application
  startup, regardless of Ollama's availability (§5).
- **BR-02**: Each dataset question is run through the real
  `RagQueryService.answer(...)` — the same code path a live `/query` call
  uses. The eval harness must not shortcut or reimplement retrieval or
  generation.
- **BR-03**: For a question resolving `ANSWERED`, faithfulness and
  completeness are scored by `JudgeService` (LLM-as-judge). For a question
  resolving `INSUFFICIENT_CONTEXT`, `correctlyDeclined` is set by comparing
  the dataset's `answerable` flag to the actual status — `JudgeService` is
  not called for this dimension.
- **BR-04**: A judge call that fails, times out, or returns text that
  cannot be parsed into scores must not raise an exception that aborts the
  run. The corresponding entry's `method` is `UNSCORABLE`, and the run
  continues to the next question.
- **BR-05**: A question whose call to `RagQueryService.answer()` itself
  throws (e.g. Gemini fully unreachable) is recorded as a failed entry with
  the error message; the run continues to the next question. One failing
  question must never abort the whole eval run.
- **BR-06**: `POST /api/eval/run` returns `503` immediately, without
  running any questions, if the eval project has zero indexed documents.
- **BR-07**: `averageFaithfulness`/`averageCompleteness` are computed only
  over `ANSWERED` questions with `method: JUDGED`.
  `correctlyDeclinedRate` is computed only over questions with
  `expectedAnswerable: false`. Neither average may be silently blended with
  the other track's data.
- **BR-08**: `summary.passed` is `true` only when both
  `averageFaithfulness` and `averageCompleteness` meet their configured
  minimums (`devassist.eval.min-faithfulness`,
  `devassist.eval.min-completeness`) AND `correctlyDeclinedRate` meets
  `devassist.eval.min-correctly-declined-rate`. Any `failedQuestions > 0`
  also makes `passed` false — an inconclusive run is not a passing one.

## 8. Error Conditions

| Condition | Status | Handling |
|---|---|---|
| Eval corpus not yet seeded (0 documents) | 503 | `EvaluationService` throws `EvalCorpusNotReadyException`; `RagExceptionHandler` gains a new `@ExceptionHandler(EvalCorpusNotReadyException.class)` entry mapped to 503 (kept separate from the AI-failure handler — this is a setup-order problem, not an AI provider failure), and `EvalController.class` is added to its `assignableTypes`. Message: `"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet."` |
| Judge call fails for one question | 200 (whole run) | That entry: `method: UNSCORABLE`, `reasoning` holds the error message |
| Judge response is not valid JSON (with or without markdown fences) | 200 (whole run) | Same `UNSCORABLE` treatment as above — defensive parsing, never an exception |
| `RagQueryService.answer()` throws for one question | 200 (whole run) | That entry has no `response`; a `failureReason` field holds the error message; counted in `failedQuestions` |

## 9. Configuration

`@ConfigurationProperties("devassist.eval")`:

| Property | Default | Meaning |
|---|---|---|
| `project-name` | `RAG Evaluation Corpus` | Name of the dedicated eval project |
| `dataset-path` | `classpath:eval/eval-dataset.json` | Location of the question set |
| `min-faithfulness` | `4.0` | Minimum average (out of 5) to pass |
| `min-completeness` | `4.0` | Minimum average (out of 5) to pass |
| `min-correctly-declined-rate` | `1.0` | Minimum fraction (0-1) of unanswerable questions correctly declined to pass |

## 10. Non-Functional Requirements

- No new dependencies. Judge output is JSON parsed with Jackson's
  `ObjectMapper`, already on the classpath via Spring Boot.
- Constructor injection throughout; immutable records for all DTOs; thin
  controller.
- The two seeded documents and the dataset file are the only new
  resources; no existing document, project, or RAG-core file is modified
  except `RagAnswerResponse` (§4).
- Judge prompt and parsing logic live entirely in `JudgeService` — nothing
  else constructs a judging prompt.

## 11. Testing

Tests must pass with no Ollama, no Gemini key, and no network access —
same standing rule as the RAG core.

- **`JudgeServiceTest`** (mocked `ChatClient`): valid JSON parses into the
  correct scores; JSON wrapped in markdown code fences still parses;
  garbage/non-JSON output produces `UNSCORABLE` rather than throwing;
  missing individual fields in an otherwise-valid JSON object are handled
  without throwing.
- **`EvaluationServiceTest`** (mocked `RagQueryService`, `JudgeService`):
  an `ANSWERED` result triggers a judge call and the score is attached
  (BR-03); an `INSUFFICIENT_CONTEXT` result does NOT trigger a judge call
  and `correctlyDeclined` is set programmatically (BR-03); a judge failure
  on one question does not stop the loop (BR-04); a
  `RagQueryService.answer()` failure on one question does not stop the
  loop (BR-05); aggregate averages are computed over the correct subsets
  only (BR-07); the `passed` flag correctly reflects both thresholds and
  `failedQuestions > 0` (BR-08).
- **`EvalCorpusSeederTest`**: a thrown exception from the underlying
  ingestion call is caught and logged, never propagated (BR-01) — inject a
  failing collaborator directly rather than asserting on real startup
  behaviour.
- **`EvalControllerTest`** (`@WebMvcTest`): `200` with a well-formed report
  on success; `503` when the corpus has zero documents (BR-06).

Run `./mvnw test` after implementation.
