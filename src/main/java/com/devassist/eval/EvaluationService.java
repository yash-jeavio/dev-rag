package com.devassist.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.springframework.stereotype.Service;

import com.devassist.document.Document;
import com.devassist.document.DocumentService;
import com.devassist.rag.EvaluationScore;
import com.devassist.rag.IndexStatus;
import com.devassist.rag.IndexStatusService;
import com.devassist.rag.RagAnswerResponse;
import com.devassist.rag.RagQueryService;

@Service
public class EvaluationService {

	private final EvalCorpusLocator locator;
	private final EvalDataset dataset;
	private final DocumentService documentService;
	private final IndexStatusService indexStatusService;
	private final RagQueryService ragQueryService;
	private final JudgeService judgeService;
	private final EvalProperties properties;

	public EvaluationService(EvalCorpusLocator locator, EvalDataset dataset, DocumentService documentService,
			IndexStatusService indexStatusService, RagQueryService ragQueryService, JudgeService judgeService,
			EvalProperties properties) {
		this.locator = locator;
		this.dataset = dataset;
		this.documentService = documentService;
		this.indexStatusService = indexStatusService;
		this.ragQueryService = ragQueryService;
		this.judgeService = judgeService;
		this.properties = properties;
	}

	public EvalReportResponse runEvaluation() {
		String projectId = locator.findProjectId().orElseThrow(EvalCorpusNotReadyException::new);
		assertCorpusIsIndexed(projectId);

		List<EvalResultEntry> entries = new ArrayList<>();
		for (EvalQuestion question : dataset.questions()) {
			entries.add(evaluateOne(projectId, question));
		}
		return new EvalReportResponse(entries, summarize(entries));
	}

	// BR-06: zero documents, or documents that exist but never finished
	// indexing (e.g. Ollama was down when EvalCorpusSeeder ran), both mean
	// there is nothing real to query against.
	private void assertCorpusIsIndexed(String projectId) {
		List<Document> documents = documentService.findByProject(projectId);
		boolean anyIndexed = documents.stream()
				.map(doc -> indexStatusService.get(doc.id()))
				.anyMatch(status -> status.isPresent() && status.get().state() == IndexStatus.State.INDEXED);
		if (!anyIndexed) {
			throw new EvalCorpusNotReadyException();
		}
	}

	private EvalResultEntry evaluateOne(String projectId, EvalQuestion question) {
		RagAnswerResponse response;
		try {
			response = ragQueryService.answer(projectId, question.question());
		}
		catch (RuntimeException ex) {
			// BR-05: one failing question must never abort the whole run.
			return new EvalResultEntry(question.question(), question.answerable(), null, false, ex.getMessage());
		}

		boolean answered = response.status() == RagAnswerResponse.Status.ANSWERED;
		EvaluationScore score = answered ? judgeSafely(question, response) : programmaticScore(question, response);

		RagAnswerResponse scored = new RagAnswerResponse(response.question(), response.answer(), response.status(),
				response.sources(), response.latencyMs(), score);
		boolean matched = answered == question.answerable();
		return new EvalResultEntry(question.question(), question.answerable(), scored, matched, null);
	}

	// BR-04: JudgeService's own parse() already turns a malformed RESPONSE into
	// UNSCORABLE internally, but judgeAnswered() can still throw before that -
	// its lazy Gemini bean resolution can fail (BeanCreationException, same as
	// GenerationService), or the live call itself can fail (ApiException /
	// NonTransientAiException). Either must degrade to UNSCORABLE here too,
	// not propagate and abort the whole run.
	private EvaluationScore judgeSafely(EvalQuestion question, RagAnswerResponse response) {
		try {
			return judgeService.judgeAnswered(question.question(), response.answer(), response.sources());
		}
		catch (RuntimeException ex) {
			return new EvaluationScore(null, null, null, "Judge call failed: " + ex.getMessage(),
					EvaluationScore.Method.UNSCORABLE);
		}
	}

	private EvaluationScore programmaticScore(EvalQuestion question, RagAnswerResponse response) {
		return new EvaluationScore(null, null, !question.answerable(),
				"Question expected answerable=" + question.answerable() + "; system returned " + response.status(),
				EvaluationScore.Method.PROGRAMMATIC);
	}

	private EvalSummary summarize(List<EvalResultEntry> entries) {
		List<EvalResultEntry> withResponse = entries.stream().filter(e -> e.response() != null).toList();

		// answerableCount/unanswerableCount describe the DATASET, not the
		// outcome - a question that failed outright (no response) still counts
		// toward whichever bucket it was drawn from, matching the spec's
		// response example where these two always sum to totalQuestions.
		int answerableCount = (int) entries.stream().filter(EvalResultEntry::expectedAnswerable).count();
		int unanswerableCount = entries.size() - answerableCount;

		List<EvalResultEntry> answerableWithResponse = withResponse.stream()
				.filter(EvalResultEntry::expectedAnswerable)
				.toList();
		List<EvalResultEntry> judged = answerableWithResponse.stream()
				.filter(e -> e.response().evaluation().method() == EvaluationScore.Method.JUDGED)
				.toList();
		List<EvalResultEntry> unanswerableWithResponse = withResponse.stream()
				.filter(entry -> !entry.expectedAnswerable())
				.toList();

		// BR-04 + the spec's error table: a query-level failure (no response
		// at all) AND a judge-level failure (response exists, but its
		// evaluation is UNSCORABLE) both count as failed - an inconclusive
		// run must not look clean just because RagQueryService itself succeeded.
		long unscorableCount = answerableWithResponse.size() - judged.size();
		int failed = (entries.size() - withResponse.size()) + (int) unscorableCount;

		// BR-07: faithfulness/completeness only over ANSWERED+JUDGED results.
		OptionalDouble avgFaithfulness = judged.stream()
				.mapToInt(e -> e.response().evaluation().faithfulness())
				.average();
		OptionalDouble avgCompleteness = judged.stream()
				.mapToInt(e -> e.response().evaluation().completeness())
				.average();
		OptionalDouble correctlyDeclinedRate = unanswerableWithResponse.isEmpty() ? OptionalDouble.empty()
				: OptionalDouble.of(unanswerableWithResponse.stream()
						.filter(e -> Boolean.TRUE.equals(e.response().evaluation().correctlyDeclined()))
						.count() / (double) unanswerableWithResponse.size());

		// BR-08: an inconclusive run (any failure) is not a passing one,
		// regardless of how good the scores that DID complete look.
		boolean passed = failed == 0
				&& avgFaithfulness.orElse(0) >= properties.minFaithfulness()
				&& avgCompleteness.orElse(0) >= properties.minCompleteness()
				&& correctlyDeclinedRate.orElse(0) >= properties.minCorrectlyDeclinedRate();

		return new EvalSummary(entries.size(), answerableCount, unanswerableCount,
				avgFaithfulness.isPresent() ? avgFaithfulness.getAsDouble() : null,
				avgCompleteness.isPresent() ? avgCompleteness.getAsDouble() : null,
				correctlyDeclinedRate.isPresent() ? correctlyDeclinedRate.getAsDouble() : null, failed, passed);
	}
}
