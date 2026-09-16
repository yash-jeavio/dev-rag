package com.devassist.rag;

import com.google.genai.Client;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * Spring eagerly instantiates every singleton bean at application startup by
 * default. Spring AI's Gemini auto-configuration registers the chat model and
 * its underlying {@code com.google.genai.Client} as plain (non-lazy)
 * singletons, and the client's factory method validates GEMINI_API_KEY the
 * moment it is constructed - so without this post-processor, the whole
 * application refuses to start whenever the key is unset, even though
 * ingestion, listing, and index-status never touch Gemini, and BR-08
 * guarantees the chat model is never called when retrieval finds nothing.
 *
 * Marking just these two bean definitions lazy defers their construction (and
 * therefore the API-key validation) to the first real query/summarize call,
 * instead of a blanket spring.main.lazy-initialization, which would hide
 * unrelated startup failures across the whole app. This is the intentional,
 * documented exception to "constructor injection only": ChatProviderService
 * resolves each concrete ChatModel via ObjectProvider rather than a direct
 * instance, so its own construction does not force these now-lazy beans into
 * existence early. GenerationService, JudgeService, and SummaryGenerator are
 * plain constructor injection of ChatProviderService itself - none of them
 * carry this exception directly any more.
 */
@Component
public class GeminiLazyChatModelConfiguration implements BeanFactoryPostProcessor {

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		for (String beanName : beanFactory.getBeanNamesForType(ChatModel.class)) {
			beanFactory.getBeanDefinition(beanName).setLazyInit(true);
		}
		for (String beanName : beanFactory.getBeanNamesForType(Client.class)) {
			beanFactory.getBeanDefinition(beanName).setLazyInit(true);
		}
	}
}
