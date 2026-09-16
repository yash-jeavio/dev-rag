package com.devassist.rag;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import com.devassist.eval.JudgeService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the incident where SummaryGenerator was missed by the
 * original provider-switching migration: adding a second ChatModel bean
 * (OpenAI's) makes Spring AI's own autoconfigured ChatClient.Builder bean
 * ambiguous (it needs exactly one ChatModel candidate). That ambiguity does
 * NOT surface as a context-load/wiring failure for a consumer depending on
 * ObjectProvider<ChatClient.Builder> - ObjectProvider always satisfies
 * injection at refresh time and only resolves (and fails) inside a real
 * getObject() call, i.e. inside a live summarize()/generate()/judge() call.
 * A @SpringBootTest context-load assertion alone therefore cannot catch this
 * class of regression - confirmed by running an earlier version of this test
 * against the pre-fix code and observing it pass anyway. This test instead
 * directly inspects every chat consumer's constructor and fails if any of
 * them still depends on ChatClient.Builder or ObjectProvider<ChatClient.Builder>
 * at all: the only supported way to obtain a chat client in this app is
 * ChatProviderService.activeChatClientBuilder().
 */
class ChatProviderWiringTest {

	@Test
	void noChatConsumerDependsOnTheAmbiguousAutoconfiguredBuilder() {
		assertThat(constructorParameterTypes(SummaryGenerator.class)).noneMatch(this::isChatClientBuilderOrProviderOfIt);
		assertThat(constructorParameterTypes(GenerationService.class)).noneMatch(this::isChatClientBuilderOrProviderOfIt);
		assertThat(constructorParameterTypes(JudgeService.class)).noneMatch(this::isChatClientBuilderOrProviderOfIt);
	}

	private List<Type> constructorParameterTypes(Class<?> type) {
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		return List.of(constructor.getGenericParameterTypes());
	}

	private boolean isChatClientBuilderOrProviderOfIt(Type type) {
		if (type == ChatClient.Builder.class) {
			return true;
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ObjectProvider.class) {
			return parameterized.getActualTypeArguments()[0] == ChatClient.Builder.class;
		}
		return false;
	}
}
