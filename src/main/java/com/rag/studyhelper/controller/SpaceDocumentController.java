package com.rag.studyhelper.controller;

import com.rag.studyhelper.model.ChunkPreview;
import com.rag.studyhelper.model.ChunkDetail;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.IngestionJob;
import com.rag.studyhelper.service.DocumentIngestionService;
import com.rag.studyhelper.service.IngestionJobService;
import com.rag.studyhelper.utils.Results;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/spaces/{spaceId}/documents")
public class SpaceDocumentController {

    private final DocumentIngestionService documents;
    private final IngestionJobService jobs;

    public SpaceDocumentController(DocumentIngestionService documents, IngestionJobService jobs) {
        this.documents = documents;
        this.jobs = jobs;
    }

    @GetMapping
    public Results<List<DocumentInfo>> list(@PathVariable long spaceId) {
        return Results.success(documents.getIngestedDocuments(spaceId));
    }

    @PostMapping("/upload")
    public ResponseEntity<Results<IngestionJob>> upload(
            @PathVariable long spaceId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
            throws IOException {
        return ResponseEntity.accepted().body(
                Results.success("Upload queued", jobs.submitUpload(spaceId, file, idempotencyKey)));
    }

    @PostMapping("/scan")
    public ResponseEntity<Results<List<IngestionJob>>> scan(@PathVariable long spaceId)
            throws IOException {
        return ResponseEntity.accepted().body(
                Results.success("Directory scan queued", jobs.submitScan(spaceId)));
    }

    @GetMapping("/{documentId}/chunks")
    public Results<List<ChunkPreview>> chunks(
            @PathVariable long spaceId,
            @PathVariable long documentId,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return Results.success(documents.listChunkPreviews(spaceId, documentId, offset, limit));
    }

    @GetMapping("/{documentId}/chunks/{chunkId}")
    public Results<ChunkDetail> chunk(
            @PathVariable long spaceId,
            @PathVariable long documentId,
            @PathVariable long chunkId) {
        return Results.success(documents.getChunkDetail(spaceId, documentId, chunkId));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Results<IngestionJob>> delete(
            @PathVariable long spaceId,
            @PathVariable long documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.accepted().body(
                Results.success("Delete queued", jobs.submitDelete(spaceId, documentId, idempotencyKey)));
    }
}
