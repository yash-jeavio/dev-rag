package com.devassist.document;

/**
 * Outcome of an ingest operation: the stored (or matched) document, plus
 * whether it was a dedup hit against an existing document in the same
 * project rather than a freshly created one.
 */
public record IngestResult(Document document, boolean deduplicated) {
}
