package com.devassist.rag;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ChunkingServiceTest {

	private ChunkingService newService(int size, int overlap) {
		return new ChunkingService(new RagProperties(5, 0.5, size, overlap, 0.1, 300, 200000));
	}

	@Test
	void returnsSingleChunkWhenTextFitsInOneChunk() {
		List<String> chunks = newService(100, 20).chunk("short text");

		assertThat(chunks).containsExactly("short text");
	}

	@Test
	void returnsEmptyListForBlankText() {
		assertThat(newService(100, 20).chunk("   ")).isEmpty();
	}

	@Test
	void returnsEmptyListForNullText() {
		assertThat(newService(100, 20).chunk(null)).isEmpty();
	}

	@Test
	void returnsEmptyListForWhitespaceOnlyTextIncludingNewlinesAndTabs() {
		assertThat(newService(100, 20).chunk("\n\t  \n\n")).isEmpty();
	}

	@Test
	void consecutiveChunksOverlap() {
		String text = "a".repeat(250);

		List<String> chunks = newService(100, 20).chunk(text);

		assertThat(chunks).hasSizeGreaterThan(1);
		String firstTail = chunks.get(0).substring(chunks.get(0).length() - 20);
		assertThat(chunks.get(1)).startsWith(firstTail);
	}

	// The uniform-character text above can't distinguish an off-by-one overlap from a
	// correct one (any slice of "aaaa..." starts with any other slice). This test uses
	// non-repeating content and reconstructs the source from the chunks, so a 1-char
	// error in the overlap arithmetic in either direction shows up as a mismatch.
	@Test
	void overlapIsExactlyTheConfiguredLength() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 100; i++) {
			sb.append(String.format("%03d", i));
		}
		String text = sb.toString();

		List<String> chunks = newService(60, 15).chunk(text);

		assertThat(chunks).hasSizeGreaterThan(2);
		StringBuilder reconstructed = new StringBuilder(chunks.get(0));
		for (int i = 1; i < chunks.size(); i++) {
			String previous = chunks.get(i - 1);
			assertThat(chunks.get(i)).startsWith(previous.substring(previous.length() - 15));
			reconstructed.append(chunks.get(i).substring(15));
		}
		assertThat(reconstructed.toString()).isEqualTo(text);
	}

	@Test
	void neverProducesChunkLongerThanConfiguredSize() {
		String text = "word ".repeat(500);

		List<String> chunks = newService(100, 20).chunk(text);

		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(100));
	}

	@Test
	void emitsOversizedSingleWordAsItsOwnChunkRatherThanDroppingIt() {
		String giant = "x".repeat(250);

		List<String> chunks = newService(100, 20).chunk(giant);

		assertThat(String.join("", chunks)).contains("x".repeat(100));
		assertThat(chunks).isNotEmpty();
	}

	// Stronger than the brief's version above: proves every character of the oversized,
	// unbreakable token survives, not just that a run of 100 x's happens to appear
	// somewhere. Reconstructing via the overlap length would fail loudly if any chunk
	// silently dropped characters instead of hard-cutting cleanly.
	@Test
	void oversizedTokenIsFullyPreservedAcrossChunksNotDropped() {
		String giant = "x".repeat(250);

		List<String> chunks = newService(100, 20).chunk(giant);

		assertThat(chunks).isNotEmpty();
		StringBuilder reconstructed = new StringBuilder(chunks.get(0));
		for (int i = 1; i < chunks.size(); i++) {
			reconstructed.append(chunks.get(i).substring(20));
		}
		assertThat(reconstructed.toString()).isEqualTo(giant);
	}

	@Test
	void prefersParagraphBoundaries() {
		String text = "First paragraph here.\n\nSecond paragraph here.";

		List<String> chunks = newService(30, 5).chunk(text);

		assertThat(chunks.get(0)).doesNotContain("Second");
	}

	// overlap >= size would make (end - overlap) walk backward or stand still forever
	// if not clamped. This must terminate promptly and must not silently drop text.
	@Test
	void doesNotHangWhenOverlapIsNotSmallerThanChunkSize() {
		String text = "word ".repeat(200);

		List<String> chunks = assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> newService(20, 1000).chunk(text));

		assertThat(chunks).isNotEmpty();
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(20));
	}
}
