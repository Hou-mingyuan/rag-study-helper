package com.rag.studyhelper.model;

/**
 * A retrieved chunk surfaced to the client before LLM streaming.
 */
public class RetrievalSnippet {

    private String documentName;
    private String text;
    private Long documentId;

    public RetrievalSnippet() {
    }

    public RetrievalSnippet(String documentName, String text) {
        this.documentName = documentName;
        this.text = text;
    }

    public RetrievalSnippet(String documentName, String text, Long documentId) {
        this.documentName = documentName;
        this.text = text;
        this.documentId = documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }
}
