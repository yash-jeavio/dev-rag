package com.devassist.rag;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class IndexStatusService {

	private final Map<String, IndexStatus> statuses = new ConcurrentHashMap<>();

	public Optional<IndexStatus> get(String documentId) {
		return Optional.ofNullable(statuses.get(documentId));
	}

	void record(IndexStatus status) {
		statuses.put(status.documentId(), status);
	}

	void remove(String documentId) {
		statuses.remove(documentId);
	}
}
