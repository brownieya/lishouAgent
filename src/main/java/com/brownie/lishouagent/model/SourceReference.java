package com.brownie.lishouagent.model;

import org.springframework.ai.document.Document;

public record SourceReference(String id, String title, String section, String sourcePath, String updatedAt) {
    public static SourceReference from(Document document) {
        return new SourceReference(document.getId(), value(document, "title"), value(document, "section"),
                value(document, "sourcePath"), value(document, "updatedAt"));
    }

    private static String value(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }
}
