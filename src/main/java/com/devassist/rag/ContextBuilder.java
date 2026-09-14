package com.devassist.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class ContextBuilder {

	private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

	private final RagProperties properties;

	public ContextBuilder(RagProperties properties) {
		this.properties = properties;
	}

	public String buildContext(List<Document> chunks) {
		StringBuilder context = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			Document chunk = chunks.get(i);
			context.append('[').append(i + 1).append("] ").append(chunk.getText()).append('\n')
					.append("Source: ").append(title(chunk))
					.append(" (chunk ").append(chunkIndex(chunk)).append(")\n\n");
		}
		return context.toString().strip();
	}

	public List<SourceReference> toSources(List<Document> chunks, String answer) {
		Set<Integer> cited = citedIndexes(answer, chunks.size());
		List<SourceReference> sources = new ArrayList<>();
		for (int i = 0; i < chunks.size(); i++) {
			Document chunk = chunks.get(i);
			sources.add(new SourceReference(
					String.valueOf(chunk.getMetadata().getOrDefault("documentId", "unknown")),
					title(chunk),
					chunkIndex(chunk),
					similarity(chunk),
					cited.contains(i + 1),
					excerpt(chunk.getText())));
		}
		return sources;
	}

	// BR-10: markers that are absent, malformed, or out of range leave every
	// source uncited rather than raising.
	private Set<Integer> citedIndexes(String answer, int sourceCount) {
		Set<Integer> indexes = new HashSet<>();
		if (answer == null) {
			return indexes;
		}
		Matcher matcher = CITATION.matcher(answer);
		while (matcher.find()) {
			try {
				int value = Integer.parseInt(matcher.group(1));
				if (value >= 1 && value <= sourceCount) {
					indexes.add(value);
				}
			}
			catch (NumberFormatException ignored) {
				// A number too large for int (or otherwise unparseable) is simply not a valid citation.
			}
		}
		return indexes;
	}

	private String excerpt(String text) {
		if (text == null) {
			return "";
		}
		int limit = properties.maxExcerptChars();
		return (text.length() <= limit) ? text : text.substring(0, limit);
	}

	private String title(Document chunk) {
		return String.valueOf(chunk.getMetadata().getOrDefault("title", "unknown"));
	}

	private int chunkIndex(Document chunk) {
		Object value = chunk.getMetadata().get("chunkIndex");
		return (value instanceof Number number) ? number.intValue() : 0;
	}

	private double similarity(Document chunk) {
		Double score = chunk.getScore();
		return (score != null) ? score : 0.0;
	}
}
