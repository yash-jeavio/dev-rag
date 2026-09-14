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
			int end = Math.min(start + size, normalised.length());
			if (end < normalised.length()) {
				end = preferredBoundary(normalised, start, end);
			}
			String chunkText = normalised.substring(start, end).strip();
			if (!chunkText.isEmpty()) {
				chunks.add(chunkText);
			}
			if (end >= normalised.length()) {
				break;
			}
			// Advance past the chunk, then step back by the overlap so the next
			// chunk repeats the tail of this one. The floor of start+1 guarantees
			// forward progress even at the overlap clamp's edge case (size - 1).
			start = Math.max(end - overlap, start + 1);
		}
		return chunks;
	}

	// Walks back from the hard cut to the nearest paragraph, then sentence, then
	// word boundary, as long as it isn't so far back that the chunk would shrink
	// to less than half its target size. Falls back to the hard cut so an
	// oversized unbroken token is cut cleanly rather than dropped.
	private int preferredBoundary(String text, int start, int hardEnd) {
		int minimum = start + (hardEnd - start) / 2;

		int paragraph = text.lastIndexOf("\n\n", hardEnd);
		if (paragraph > minimum) {
			return paragraph;
		}
		for (String terminator : List.of(". ", "! ", "? ", "\n")) {
			int sentence = text.lastIndexOf(terminator, hardEnd);
			if (sentence > minimum) {
				return sentence + terminator.length();
			}
		}
		int space = text.lastIndexOf(' ', hardEnd);
		return (space > minimum) ? space : hardEnd;
	}
}
