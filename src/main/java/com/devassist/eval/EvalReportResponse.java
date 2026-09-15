package com.devassist.eval;

import java.util.List;

public record EvalReportResponse(List<EvalResultEntry> results, EvalSummary summary) {
}
