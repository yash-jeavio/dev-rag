package com.devassist.eval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.devassist.document.Document;
import com.devassist.document.DocumentService;
import com.devassist.document.SourceType;
import com.devassist.rag.EvaluationScore;
import com.devassist.rag.IndexStatus;
import com.devassist.rag.IndexStatusService;
import com.devassist.rag.RagAnswerResponse;
import com.devassist.rag.RagQueryService;
import com.devassist.rag.SourceReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

	private EvalCorpusLocator locator;
	private EvalDataset dataset;
	private DocumentService documentService;
	private IndexStatusService indexStatusService;
	private RagQueryService ragQueryService;
	private JudgeService judgeService;
	private EvalProperties properties;
	private EvaluationService service;

	private final Document indexedDoc = new Document("doc-1", "eval-proj", "product-policy.txt", SourceType.TEXT,
			"body", "hash", Instant.now());

	@BeforeEach
	void setUp() {
		locator = mock(EvalCorpusLocator.class);
		dataset = mock(EvalDataset.class);
		documentService = mock(DocumentService.class);
		indexStatusService = mock(IndexStatusService.class);
		ragQueryService = mock(RagQueryService.class);
		judgeService = mock(JudgeService.class);
		properties = new EvalProperties("RAG Evaluation Corpus", "classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);
		service = new EvaluationService(locator, dataset, documentService, indexStatusService, ragQueryService,
				judgeService, properties);

		when(locator.findProjectId()).thenReturn(Optional.of("eval-proj"));
		when(documentService.findByProject("eval-proj")).thenReturn(List.of(indexedDoc));
		when(indexStatusService.get("doc-1"))
				.thenReturn(Optional.of(new IndexStatus("doc-1", IndexStatus.State.INDEXED, 1, null)));
	}

	@Test
	void throwsCorpusNotReadyWhenNoDocumentsExist() {
		when(documentService.findByProject("eval-proj")).thenReturn(List.of());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runEvaluation())
				.isInstanceOf(EvalCorpusNotReadyException.class);
	}

	@Test
	void throwsCorpusNotReadyWhenDocumentsExistButNoneAreIndexedYet() {
		when(indexStatusService.get("doc-1"))
				.thenReturn(Optional.of(new IndexStatus("doc-1", IndexStatus.State.FAILED, 0, "embedding down")));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runEvaluation())
				.isInstanceOf(EvalCorpusNotReadyException.class);
	}

	@Test
	void answeredQuestionsAreScoredByTheJudgeNotProgrammatically() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		RagAnswerResponse answered = new RagAnswerResponse("q1", "30 days. [1]", RagAnswerResponse.Status.ANSWERED,
				List.of(source), 100, null);
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(answered);
		EvaluationScore judged = new EvaluationScore(5, 5, null, "great", EvaluationScore.Method.JUDGED);
		when(judgeService.judgeAnswered(eq("q1"), eq("30 days. [1]"), any())).thenReturn(judged);

		List<EvalResultEntry> results = service.runEvaluation().results();

		verify(judgeService).judgeAnswered(eq("q1"), eq("30 days. [1]"), any());
		assertThat(results).hasSize(1);
		assertThat(results.get(0).response().evaluation()).isEqualTo(judged);
		assertThat(results.get(0).outcomeMatchedExpectation()).isTrue();
	}

	@Test
	void insufficientContextQuestionsAreScoredProgrammaticallyNotByTheJudge() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("unanswerable q", false)));
		RagAnswerResponse declined = new RagAnswerResponse("unanswerable q", RagQueryService.NO_CONTEXT_ANSWER,
				RagAnswerResponse.Status.INSUFFICIENT_CONTEXT, List.of(), 50, null);
		when(ragQueryService.answer("eval-proj", "unanswerable q")).thenReturn(declined);

		EvalReportResponse report = service.runEvaluation();

		verify(judgeService, never()).judgeAnswered(anyString(), anyString(), any());
		EvalResultEntry entry = report.results().get(0);
		EvaluationScore score = entry.response().evaluation();
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.PROGRAMMATIC);
		assertThat(score.correctlyDeclined()).isTrue();
		assertThat(entry.outcomeMatchedExpectation()).isTrue();
		// Fix 1a: a correctly-scored PROGRAMMATIC entry must not be miscounted
		// as an unscorable judge failure, and it is not a mismatched outcome.
		assertThat(report.summary().failedQuestions()).isEqualTo(0);
		assertThat(report.summary().mismatchedOutcomes()).isEqualTo(0);
	}

	// Fix 1b: BR-08's "outcome didn't match expectation" signal must be a
	// real, separately-counted pass/fail term - not just per-entry decoration
	// that a caller could ignore. An answerable question that the system
	// wrongly declines is scored PROGRAMMATIC (never reaches the judge, so
	// it can't be "unscorable"), yet it must still fail the run.
	@Test
	void flagsAMismatchWhenAnAnswerableQuestionIsWronglyDeclined() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		RagAnswerResponse declined = new RagAnswerResponse("q1", RagQueryService.NO_CONTEXT_ANSWER,
				RagAnswerResponse.Status.INSUFFICIENT_CONTEXT, List.of(), 50, null);
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(declined);

		EvalReportResponse report = service.runEvaluation();
		EvalResultEntry entry = report.results().get(0);

		assertThat(entry.response().evaluation().correctlyDeclined()).isFalse();
		assertThat(entry.outcomeMatchedExpectation()).isFalse();
		assertThat(report.summary().failedQuestions()).isEqualTo(0);
		assertThat(report.summary().mismatchedOutcomes()).isEqualTo(1);
		assertThat(report.summary().passed()).isFalse();
	}

	@Test
	void oneFailingQuestionDoesNotStopTheRun() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("bad", true), new EvalQuestion("good", true)));
		when(ragQueryService.answer("eval-proj", "bad")).thenThrow(new RuntimeException("Gemini down"));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		RagAnswerResponse ok = new RagAnswerResponse("good", "ans [1]", RagAnswerResponse.Status.ANSWERED,
				List.of(source), 100, null);
		when(ragQueryService.answer("eval-proj", "good")).thenReturn(ok);
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenReturn(new EvaluationScore(5, 5, null, "ok", EvaluationScore.Method.JUDGED));

		EvalReportResponse report = service.runEvaluation();

		assertThat(report.results()).hasSize(2);
		assertThat(report.results().get(0).failureReason()).isEqualTo("Gemini down");
		assertThat(report.results().get(0).response()).isNull();
		assertThat(report.results().get(1).response()).isNotNull();
		assertThat(report.summary().failedQuestions()).isEqualTo(1);
	}

	@Test
	void averagesAreComputedOverOnlyTheCorrectSubsets() {
		when(dataset.questions()).thenReturn(List.of(
				new EvalQuestion("answerable-1", true), new EvalQuestion("unanswerable-1", false)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		when(ragQueryService.answer("eval-proj", "answerable-1")).thenReturn(new RagAnswerResponse("answerable-1",
				"ans [1]", RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenReturn(new EvaluationScore(4, 5, null, "ok", EvaluationScore.Method.JUDGED));
		when(ragQueryService.answer("eval-proj", "unanswerable-1")).thenReturn(new RagAnswerResponse(
				"unanswerable-1", RagQueryService.NO_CONTEXT_ANSWER, RagAnswerResponse.Status.INSUFFICIENT_CONTEXT,
				List.of(), 50, null));

		EvalSummary summary = service.runEvaluation().summary();

		assertThat(summary.averageFaithfulness()).isEqualTo(4.0);
		assertThat(summary.averageCompleteness()).isEqualTo(5.0);
		assertThat(summary.correctlyDeclinedRate()).isEqualTo(1.0);
		assertThat(summary.answerableQuestions()).isEqualTo(1);
		assertThat(summary.unanswerableQuestions()).isEqualTo(1);
	}

	// BR-04, explicitly required by specs/rag-evaluation.md §11: a judge call
	// that throws outright (not just returns malformed text - JudgeService's
	// own parsing failures are covered in JudgeServiceTest) must not abort
	// the run either. Uses two questions - the first's judge call throws - to
	// prove the loop actually continues to the second question, not just
	// that a single isolated question degrades gracefully.
	@Test
	void aJudgeCallThatThrowsIsCountedAsFailedNotAborted() {
		when(dataset.questions())
				.thenReturn(List.of(new EvalQuestion("bad-judge", true), new EvalQuestion("good", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		when(ragQueryService.answer("eval-proj", "bad-judge")).thenReturn(new RagAnswerResponse("bad-judge",
				"ans [1]", RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(ragQueryService.answer("eval-proj", "good")).thenReturn(new RagAnswerResponse("good", "ans [1]",
				RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(eq("bad-judge"), anyString(), any()))
				.thenThrow(new RuntimeException("Gemini unreachable"));
		when(judgeService.judgeAnswered(eq("good"), anyString(), any()))
				.thenReturn(new EvaluationScore(5, 5, null, "ok", EvaluationScore.Method.JUDGED));

		EvalReportResponse report = service.runEvaluation();

		assertThat(report.results()).hasSize(2);
		assertThat(report.results().get(0).response()).isNotNull();
		assertThat(report.results().get(0).response().evaluation().method())
				.isEqualTo(EvaluationScore.Method.UNSCORABLE);
		assertThat(report.results().get(1).response()).isNotNull();
		assertThat(report.results().get(1).response().evaluation().method())
				.isEqualTo(EvaluationScore.Method.JUDGED);
		assertThat(report.summary().failedQuestions()).isEqualTo(1);
		assertThat(report.summary().passed()).isFalse();
	}

	// Distinguishes "both failure kinds are counted" from "only one is" -
	// combines a pure query-level failure (no response at all) with a
	// judge-level failure (response exists but evaluation is UNSCORABLE) in
	// the same run, alongside a clean success, and asserts failedQuestions
	// sums both terms rather than one shadowing the other.
	@Test
	void failedQuestionsCountsBothQueryLevelAndJudgeLevelFailuresTogether() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("query-fails", true),
				new EvalQuestion("judge-fails", true), new EvalQuestion("clean", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");

		when(ragQueryService.answer("eval-proj", "query-fails")).thenThrow(new RuntimeException("Gemini down"));
		when(ragQueryService.answer("eval-proj", "judge-fails")).thenReturn(new RagAnswerResponse("judge-fails",
				"ans [1]", RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(eq("judge-fails"), anyString(), any()))
				.thenThrow(new RuntimeException("Judge unreachable"));
		when(ragQueryService.answer("eval-proj", "clean")).thenReturn(new RagAnswerResponse("clean", "ans [1]",
				RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(eq("clean"), anyString(), any()))
				.thenReturn(new EvaluationScore(5, 5, null, "ok", EvaluationScore.Method.JUDGED));

		EvalReportResponse report = service.runEvaluation();

		assertThat(report.results()).hasSize(3);
		assertThat(report.results().get(0).response()).isNull();
		assertThat(report.results().get(1).response().evaluation().method())
				.isEqualTo(EvaluationScore.Method.UNSCORABLE);
		assertThat(report.results().get(2).response().evaluation().method())
				.isEqualTo(EvaluationScore.Method.JUDGED);
		assertThat(report.summary().failedQuestions()).isEqualTo(2);
	}

	@Test
	void passedIsFalseWhenAnyQuestionFailedEvenIfScoresAreHighEnough() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("bad", true)));
		when(ragQueryService.answer("eval-proj", "bad")).thenThrow(new RuntimeException("down"));

		EvalSummary summary = service.runEvaluation().summary();

		assertThat(summary.failedQuestions()).isEqualTo(1);
		assertThat(summary.passed()).isFalse();
	}

	@Test
	void passedIsFalseWhenFaithfulnessIsBelowTheConfiguredMinimum() {
		properties = new EvalProperties("RAG Evaluation Corpus", "classpath:eval/eval-dataset.json", 4.5, 4.0, 1.0);
		service = new EvaluationService(locator, dataset, documentService, indexStatusService, ragQueryService,
				judgeService, properties);
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(new RagAnswerResponse("q1", "ans [1]",
				RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenReturn(new EvaluationScore(3, 5, null, "weak", EvaluationScore.Method.JUDGED));

		EvalSummary summary = service.runEvaluation().summary();

		assertThat(summary.passed()).isFalse();
	}
}
