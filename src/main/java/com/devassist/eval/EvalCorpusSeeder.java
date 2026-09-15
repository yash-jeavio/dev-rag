package com.devassist.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import com.devassist.document.DocumentService;
import com.devassist.document.IngestTextRequest;
import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectService;

@Component
public class EvalCorpusSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(EvalCorpusSeeder.class);

	private final ProjectService projectService;
	private final DocumentService documentService;
	private final EvalCorpusLocator locator;
	private final EvalProperties properties;
	private final ResourceLoader resourceLoader;

	public EvalCorpusSeeder(ProjectService projectService, DocumentService documentService,
			EvalCorpusLocator locator, EvalProperties properties, ResourceLoader resourceLoader) {
		this.projectService = projectService;
		this.documentService = documentService;
		this.locator = locator;
		this.properties = properties;
		this.resourceLoader = resourceLoader;
	}

	// BR-01: must never block or fail application startup, regardless of
	// Ollama's availability - a failure here only means the eval corpus is not
	// ready yet, which /api/eval/run reports on its own (EvalCorpusNotReadyException).
	@Override
	public void run(ApplicationArguments args) {
		try {
			String projectId = locator.findProjectId().orElseGet(this::createEvalProject);
			// Content-hash dedup (rag-core BR-03) makes re-ingesting on every
			// restart a safe no-op once a document already exists, so there is
			// no need to check for existing documents before calling ingestText.
			documentService.ingestText(projectId, new IngestTextRequest("product-policy.txt",
					readResource("classpath:eval/product-policy.txt")));
			documentService.ingestText(projectId, new IngestTextRequest("engineering-practices.txt",
					readResource("classpath:eval/engineering-practices.txt")));
		}
		catch (Exception ex) {
			log.warn("Eval corpus seeding failed; POST /api/eval/run will report 503 until this succeeds", ex);
		}
	}

	private String createEvalProject() {
		Project project = projectService.create(new CreateProjectRequest(properties.projectName(),
				"Fixed corpus for the RAG evaluation harness", "N/A", "https://example.com/devassist-eval"));
		return project.id();
	}

	private String readResource(String location) {
		Resource resource = resourceLoader.getResource(location);
		try {
			return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Missing bundled eval resource: " + location, ex);
		}
	}
}
