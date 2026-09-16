package com.devassist.rag;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatProviderController {

	private final ChatProviderService chatProviderService;

	public ChatProviderController(ChatProviderService chatProviderService) {
		this.chatProviderService = chatProviderService;
	}

	@GetMapping("/api/settings/chat-provider")
	public Map<String, ChatProvider> get() {
		return Map.of("provider", chatProviderService.get());
	}

	@PutMapping("/api/settings/chat-provider")
	public Map<String, ChatProvider> set(@Valid @RequestBody ChatProviderRequest request) {
		chatProviderService.set(request.provider());
		return Map.of("provider", chatProviderService.get());
	}
}
