package com.rag.studyhelper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalSnippet {

    private String documentName;
    private String text;
    private Long documentId;
    private Long chunkId;
    private Integer chunkIndex;
    private Integer pageNumber;
    private String sectionTitle;
    private Double retrievalScore;
    private Double rerankScore;
    private String rerankStatus;
    private String vectorId;

    public RetrievalSnippet(String documentName, String text) {
        this.documentName = documentName;
        this.text = text;
    }

    public RetrievalSnippet(String documentName, String text, Long documentId) {
        this.documentName = documentName;
        this.text = text;
        this.documentId = documentId;
    }
}
