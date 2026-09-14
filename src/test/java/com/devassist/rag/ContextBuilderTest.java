package com.devassist.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderTest {

	private final ContextBuilder builder =
			new ContextBuilder(new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000));

	private Document chunk(String text, String documentId, String title, int index) {
		return new Document(text, Map.of(
				"projectId", "proj-1", "documentId", documentId, "title", title,
				"sourceType", "TEXT", "chunkIndex", index));
	}

	@Test
	void numbersContextBlocksFromOne() {
		String context = builder.buildContext(List.of(
				chunk("first text", "doc-1", "a.md", 0),
				chunk("second text", "doc-2", "b.md", 0)));

		assertThat(context).contains("[1]").contains("first text");
		assertThat(context).contains("[2]").contains("second text");
	}

	@Test
	void marksSourcesCitedWhenTheAnswerReferencesThem() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("first", "doc-1", "a.md", 0), chunk("second", "doc-2", "b.md", 1)),
				"The answer is here. [1]");

		assertThat(sources.get(0).cited()).isTrue();
		assertThat(sources.get(1).cited()).isFalse();
	}

	@Test
	void toleratesAnswerWithNoCitationMarkers() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("first", "doc-1", "a.md", 0)), "No markers at all.");

		assertThat(sources).hasSize(1);
		assertThat(sources.get(0).cited()).isFalse();
	}

	@Test
	void ignoresOutOfRangeCitationMarkers() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("first", "doc-1", "a.md", 0)), "Nonsense [7] marker.");

		assertThat(sources.get(0).cited()).isFalse();
	}

	@Test
	void truncatesExcerptToConfiguredLength() {
		ContextBuilder shortExcerpts =
				new ContextBuilder(new RagProperties(5, 0.5, 2000, 200, 0.1, 10, 200000));

		List<SourceReference> sources = shortExcerpts.toSources(
				List.of(chunk("a".repeat(50), "doc-1", "a.md", 0)), "[1]");

		assertThat(sources.get(0).excerpt()).hasSizeLessThanOrEqualTo(10);
	}

	@Test
	void marksBothSourcesCitedWhenMarkersAppearOutOfOrder() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("alpha content", "doc-1", "a.md", 0),
						chunk("beta content", "doc-2", "b.md", 0),
						chunk("gamma content", "doc-3", "c.md", 0)),
				"As shown in [2] and confirmed earlier in [1].");

		assertThat(sources.get(0).cited()).isTrue();
		assertThat(sources.get(1).cited()).isTrue();
		assertThat(sources.get(2).cited()).isFalse();
	}

	@Test
	void repeatedMarkerStillCitesOnlyOnce() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("alpha content", "doc-1", "a.md", 0),
						chunk("beta content", "doc-2", "b.md", 0)),
				"See [1]. Again, [1] confirms it.");

		assertThat(sources.get(0).cited()).isTrue();
		assertThat(sources.get(1).cited()).isFalse();
	}

	@Test
	void marksSourceUncitedForBelowRangeMarkerZero() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("alpha content", "doc-1", "a.md", 0)), "Cites nothing real: [0]");

		assertThat(sources.get(0).cited()).isFalse();
	}

	@Test
	void toleratesCitationNumberLargerThanIntegerMaxValue() {
		List<SourceReference> sources = builder.toSources(
				List.of(chunk("alpha content", "doc-1", "a.md", 0)),
				"Way out of range: [99999999999999999999]");

		assertThat(sources.get(0).cited()).isFalse();
	}

	@Test
	void excerptExactlyAtConfiguredLimitIsNotTruncated() {
		ContextBuilder shortExcerpts =
				new ContextBuilder(new RagProperties(5, 0.5, 2000, 200, 0.1, 10, 200000));
		String exactLength = "0123456789";

		List<SourceReference> sources = shortExcerpts.toSources(
				List.of(chunk(exactLength, "doc-1", "a.md", 0)), "[1]");

		assertThat(sources.get(0).excerpt()).isEqualTo(exactLength);
	}

	@Test
	void excerptOneCharacterOverConfiguredLimitIsTruncated() {
		ContextBuilder shortExcerpts =
				new ContextBuilder(new RagProperties(5, 0.5, 2000, 200, 0.1, 10, 200000));
		String oneOver = "0123456789X";

		List<SourceReference> sources = shortExcerpts.toSources(
				List.of(chunk(oneOver, "doc-1", "a.md", 0)), "[1]");

		assertThat(sources.get(0).excerpt()).isEqualTo("0123456789");
	}
}
