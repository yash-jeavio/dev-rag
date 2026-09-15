package com.devassist.eval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

	private final EvaluationService evaluationService;

	public EvalController(EvaluationService evaluationService) {
		this.evaluationService = evaluationService;
	}

	@PostMapping("/run")
	public ResponseEntity<EvalReportResponse> run() {
		return ResponseEntity.ok(evaluationService.runEvaluation());
	}
}
