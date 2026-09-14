package com.devassist.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Implements BR-02: splits text into overlapping chunks on paragraph, then
 * sentence, then word boundaries, targeting {@code chunkSizeChars} with
 * {@code chunkOverlapChars} of overlap between consecutive chunks.
 */
@Service
public class ChunkingService {

	private final RagProperties properties;

	public ChunkingService(RagProperties properties) {
		this.properties = properties;
	}

	public List<String> chunk(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}

		String normalised = text.strip();
		int size = properties.chunkSizeChars();
		// Clamped below size so (end - overlap) always advances start forward;
		// otherwise overlap >= size would make the loop stall or run backward.
		int overlap = Math.min(properties.chunkOverlapChars(), size - 1);

		List<String> chunks = new ArrayList<>();
		int start = 0;
		while (start < normalised.length()) {
			int hardEnd = Math.min(start + size, normalised.length());
			int end = hardEnd;
			if (end < normalised.length()) {
				end = Math.min(preferredBoundary(normalised, start, hardEnd), hardEnd);
			}
			String raw = normalised.substring(start, end);
			// Trailing-only: a boundary cut (e.g. the space after ". ") can leave
			// trailing whitespace in `raw`. Stripping it here, and deriving trueEnd
			// from the STRIPPED length rather than the raw `end`, is what keeps the
			// next chunk's start aligned exactly `overlap` characters back from what
			// was actually stored - not from a position that includes whitespace
			// that never made it into this chunk. Leading whitespace is left alone
			// deliberately: stripping it off a continuation chunk would shift its
			// stored start past the position computed below, again shorting the
			// overlap by however much was trimmed.
			String chunkText = raw.stripTrailing();
			int trueEnd = start + chunkText.length();
			if (!chunkText.isEmpty()) {
				chunks.add(chunkText);
			}
			if (end >= normalised.length()) {
				break;
			}
			// Advance past the chunk, then step back by the overlap so the next
			// chunk repeats the tail of this one. The floor of start+1 guarantees
			// forward progress even at the overlap clamp's edge case (size - 1).
			start = Math.max(trueEnd - overlap, start + 1);
		}
		return chunks;
	}

	// Walks back from the hard cut to the nearest paragraph, then sentence, then
	// word boundary, as long as it isn't so far back that the chunk would shrink
	// to less than half its target size. Falls back to the hard cut so an
	// oversized unbroken token is cut cleanly rather than dropped.
	//
	// Each lastIndexOf search is bounded to hardEnd - terminator.length(): Java's
	// lastIndexOf(String, int) treats fromIndex as a legal MATCH-START position,
	// not a ceiling on where the match ends, so searching straight from hardEnd can
	// return a terminator that starts at hardEnd - and sentence + terminator.length()
	// then lands past hardEnd, exceeding the configured chunk size.
	private int preferredBoundary(String text, int start, int hardEnd) {
		int minimum = start + (hardEnd - start) / 2;

		int paragraph = text.lastIndexOf("\n\n", hardEnd - "\n\n".length());
		if (paragraph > minimum) {
			return paragraph;
		}
		for (String terminator : List.of(". ", "! ", "? ", "\n")) {
			int sentence = text.lastIndexOf(terminator, hardEnd - terminator.length());
			if (sentence > minimum) {
				return sentence + terminator.length();
			}
		}
		int space = text.lastIndexOf(' ', hardEnd);
		return (space > minimum) ? space : hardEnd;
	}
}
