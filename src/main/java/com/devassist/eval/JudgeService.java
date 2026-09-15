package com.devassist.eval;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.devassist.rag.ChatProviderService;
import com.devassist.rag.EvaluationScore;
import com.devassist.rag.SourceReference;

public class JudgeService {

	private static final String SYSTEM_PROMPT = """
			You are a strict evaluator of another AI's answer to a question, given the
			context it was allowed to use. Respond with ONLY a single JSON object, no
			markdown fences, no extra text, in exactly this shape:
			{"faithfulness": <1-5 integer>, "completeness": <1-5 integer>, "reasoning": "<one sentence>"}

			faithfulness: does the answer make only claims supported by the context?
			completeness: does the answer address the whole question, not just part of it?
			""";

	// Matches a JSON object even when the model wraps it in markdown code
	// fences despite being told not to - LLMs do this often enough that it
	// must be handled, not treated as exceptional.
	private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

	private final ChatProviderService chatProviderService;
	private final ObjectMapper objectMapper;

	public JudgeService(ChatProviderService chatProviderService, ObjectMapper objectMapper) {
		this.chatProviderService = chatProviderService;
		this.objectMapper = objectMapper;
	}

	public EvaluationScore judgeAnswered(String question, String answer, List<SourceReference> sources) {
		String context = buildContext(sources);
		ChatClient chatClient = chatProviderService.activeChatClientBuilder().defaultSystem(SYSTEM_PROMPT).build();
		String raw = chatClient.prompt()
				.user("Context:\n%s\n\nQuestion: %s\n\nAnswer to evaluate: %s".formatted(context, question, answer))
				.call()
				.content();
		return parse(raw);
	}

	private String buildContext(List<SourceReference> sources) {
		StringBuilder builder = new StringBuilder();
		for (SourceReference source : sources) {
			builder.append(source.title()).append(": ").append(source.excerpt()).append('\n');
		}
		return builder.toString();
	}

	// The judge's output is untrusted LLM text, same as citation-marker
	// parsing in the RAG core - it must degrade to UNSCORABLE, never throw.
	private EvaluationScore parse(String raw) {
		if (raw == null) {
			return unscorable("Judge returned no content");
		}
		Matcher matcher = JSON_OBJECT.matcher(raw);
		if (!matcher.find()) {
			return unscorable("Judge response contained no JSON object: " + raw);
		}
		try {
			JudgeJson parsed = objectMapper.readValue(matcher.group(), JudgeJson.class);
			if (parsed.faithfulness() == null || parsed.completeness() == null) {
				return unscorable("Judge response missing faithfulness or completeness: " + raw);
			}
			if (!isInRange(parsed.faithfulness()) || !isInRange(parsed.completeness())) {
				return unscorable("Judge response score out of the 1-5 range: " + raw);
			}
			return new EvaluationScore(parsed.faithfulness(), parsed.completeness(), null,
					parsed.reasoning() != null ? parsed.reasoning() : "(no reasoning given)",
					EvaluationScore.Method.JUDGED);
		}
		catch (Exception ex) {
			return unscorable("Could not parse judge response: " + ex.getMessage());
		}
	}

	private boolean isInRange(int score) {
		return score >= 1 && score <= 5;
	}

	private EvaluationScore unscorable(String reasoning) {
		return new EvaluationScore(null, null, null, reasoning, EvaluationScore.Method.UNSCORABLE);
	}

	private record JudgeJson(Integer faithfulness, Integer completeness, String reasoning) {
	}
}
