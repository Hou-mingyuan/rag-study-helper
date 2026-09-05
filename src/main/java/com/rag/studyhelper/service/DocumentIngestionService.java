package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.ingestion.ActivationResult;
import com.rag.studyhelper.ingestion.ChunkDraft;
import com.rag.studyhelper.ingestion.DocumentDescriptor;
import com.rag.studyhelper.ingestion.IndexStage;
import com.rag.studyhelper.ingestion.IngestionCancelledException;
import com.rag.studyhelper.ingestion.IngestionControl;
import com.rag.studyhelper.ingestion.IngestionLockCoordinator;
import com.rag.studyhelper.ingestion.IngestionPersistence;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.ChunkPreview;
import com.rag.studyhelper.model.ChunkDetail;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.utils.Hashing;
import com.rag.studyhelper.vector.EmbeddingGateway;
import com.rag.studyhelper.vector.VectorEntry;
import com.rag.studyhelper.vector.VectorIndexManager;
import com.rag.studyhelper.vector.VectorStoreGateway;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final int MAX_SEGMENT_TOKENS = 512;
    private static final int SEGMENT_OVERLAP_TOKENS = 64;
    private static final int PREVIEW_MAX_CHARS = 420;

    private final IngestionPersistence persistence;
    private final IngestionLockCoordinator ingestionLocks;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStoreGateway vectorStore;
    private final VectorIndexManager vectorIndexManager;
    private final DocumentsMapper documentsMapper;
    private final DocumentChunksMapper chunksMapper;
    private final KnowledgeSpaceService spaceService;
    private final TokenCountEstimator tokenEstimator =
            new OpenAiTokenCountEstimator(OpenAiChatModelName.GPT_3_5_TURBO);

    @Value("${app.rag.document-scan-path:data/docs}")
    private String scanPath;

    @Value("${app.rag.auto-scan:false}")
    private boolean autoScan;

    @Value("${app.rag.embedding-batch-size:10}")
    private int embeddingBatchSize;

    @Value("${app.rag.max-document-bytes:52428800}")
    private long maxDocumentBytes = 52_428_800L;

    @Value("${app.rag.max-extracted-characters:2000000}")
    private int maxExtractedCharacters = 2_000_000;

    public DocumentIngestionService(IngestionPersistence persistence,
                                    IngestionLockCoordinator ingestionLocks,
                                    EmbeddingGateway embeddingGateway,
                                    VectorStoreGateway vectorStore,
                                    VectorIndexManager vectorIndexManager,
                                    DocumentsMapper documentsMapper,
                                    DocumentChunksMapper chunksMapper,
                                    KnowledgeSpaceService spaceService) {
        this.persistence = persistence;
        this.ingestionLocks = ingestionLocks;
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.vectorIndexManager = vectorIndexManager;
        this.documentsMapper = documentsMapper;
        this.chunksMapper = chunksMapper;
        this.spaceService = spaceService;
    }

    @PostConstruct
    public void initializeOptionalScan() {
        if (autoScan) {
            scanAndIngest(KnowledgeSpaceService.DEFAULT_SPACE_ID);
        }
    }

    public List<DocumentInfo> scanAndIngest() {
        return scanAndIngest(KnowledgeSpaceService.DEFAULT_SPACE_ID);
    }

    public List<DocumentInfo> scanAndIngest(long spaceId) {
        spaceService.requireActive(spaceId);
        File directory = new File(scanPath);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create document scan directory");
        }
        File[] files = directory.listFiles((dir, name) -> isSupported(name));
        List<DocumentInfo> results = new ArrayList<>();
        if (files == null) {
            return results;
        }
        for (File file : files) {
            try (InputStream input = new FileInputStream(file)) {
                results.add(ingestBytes(spaceId, file.getName(), null, input, "SCAN",
                        file.toPath().toAbsolutePath().normalize().toString(), null));
            } catch (Exception error) {
                log.error("Document scan item failed: name={}", file.getName(), error);
            }
        }
        return results;
    }

    public DocumentInfo ingestDocument(Path filePath) throws IOException {
        try (InputStream input = new FileInputStream(filePath.toFile())) {
            return ingestBytes(KnowledgeSpaceService.DEFAULT_SPACE_ID, filePath.getFileName().toString(),
                    null, input, "UPLOAD", filePath.toAbsolutePath().normalize().toString(), null);
        }
    }

    public DocumentInfo ingestDocument(String fileName, InputStream inputStream) throws IOException {
        return ingestDocument(KnowledgeSpaceService.DEFAULT_SPACE_ID, fileName, null, inputStream);
    }

    public DocumentInfo ingestDocument(long spaceId, String fileName, String mimeType,
                                       InputStream inputStream) throws IOException {
        return ingestDocument(spaceId, fileName, mimeType, inputStream, IngestionControl.NONE);
    }

    public DocumentInfo ingestDocument(long spaceId, String fileName, String mimeType,
                                       InputStream inputStream, IngestionControl control) throws IOException {
        return ingestBytes(spaceId, fileName, mimeType, inputStream, "UPLOAD", null, null, control);
    }

    public DocumentInfo ingestScannedDocument(long spaceId, Path path,
                                              IngestionControl control) throws IOException {
        try (InputStream input = new FileInputStream(path.toFile())) {
            return ingestBytes(spaceId, path.getFileName().toString(), null, input, "SCAN",
                    path.toAbsolutePath().normalize().toString(), null, control);
        }
    }

    public DocumentInfo ingestDocument(String fileName, String content) throws IOException {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ingestParsed(new DocumentDescriptor(
                        KnowledgeSpaceService.DEFAULT_SPACE_ID,
                        sanitizeFileName(fileName), extension(fileName), "text/plain", "UPLOAD",
                        Hashing.sha256(bytes), bytes.length, null,
                        null, null, null, 0L, "system"),
                Document.from(content), IngestionControl.NONE);
    }

    public DocumentInfo ingestFeishuDocument(String fileName, String content,
                                             String nodeToken, long updateTime,
                                             String objType) throws IOException {
        return ingestFeishuDocument(KnowledgeSpaceService.DEFAULT_SPACE_ID, "default",
                fileName, content, nodeToken, updateTime, objType);
    }

    public DocumentInfo ingestFeishuDocument(long spaceId, String remoteSpaceId,
                                             String fileName, String content,
                                             String nodeToken, long updateTime,
                                             String objType) throws IOException {
        spaceService.requireActive(spaceId);
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DocumentDescriptor descriptor = new DocumentDescriptor(
                spaceId, sanitizeFileName(fileName), objType, "text/plain", "FEISHU",
                Hashing.sha256(bytes), bytes.length, null,
                remoteSpaceId, nodeToken, objType, updateTime, "feishu");
        return ingestParsed(descriptor, Document.from(content), IngestionControl.NONE);
    }

    private DocumentInfo ingestBytes(long spaceId, String fileName, String mimeType,
                                     InputStream inputStream, String source,
                                     String originalPath, String creator) throws IOException {
        return ingestBytes(spaceId, fileName, mimeType, inputStream, source, originalPath,
                creator, IngestionControl.NONE);
    }

    private DocumentInfo ingestBytes(long spaceId, String fileName, String mimeType,
                                     InputStream inputStream, String source,
                                     String originalPath, String creator,
                                     IngestionControl control) throws IOException {
        spaceService.requireActive(spaceId);
        checkCancelled(control);
        String safeName = sanitizeFileName(fileName);
        if (!isSupported(safeName)) {
            throw new IllegalArgumentException("Unsupported document type: " + extension(safeName));
        }
        int byteLimit = (int) Math.min(Math.max(1L, maxDocumentBytes), Integer.MAX_VALUE - 1L);
        byte[] content = inputStream.readNBytes(byteLimit + 1);
        if (content.length > byteLimit) {
            throw new IllegalArgumentException("Document exceeds the configured size limit");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("Document is empty");
        }
        Document document = parseDocument(safeName, new ByteArrayInputStream(content));
        control.progress("PARSING", 1, 4);
        checkCancelled(control);
        DocumentDescriptor descriptor = new DocumentDescriptor(
                spaceId, safeName, extension(safeName), mimeType, source,
                Hashing.sha256(content), content.length, originalPath,
                null, null, null, 0L, creator == null ? "local" : creator);
        return ingestParsed(descriptor, document, control);
    }

    private DocumentInfo ingestParsed(DocumentDescriptor descriptor, Document document,
                                      IngestionControl control) throws IOException {
        try (IngestionLockCoordinator.Lease ignored = ingestionLocks.acquire(descriptor)) {
            return ingestParsedLocked(descriptor, document, control);
        }
    }

    private DocumentInfo ingestParsedLocked(DocumentDescriptor descriptor, Document document,
                                            IngestionControl control) throws IOException {
        validateExtractedText(document);
        List<ChunkDraft> drafts = split(document);
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("Document contains no indexable text");
        }

        IndexStage stage = persistence.stage(descriptor, drafts);
        control.progress("STAGED", 2, 4);
        if (stage.duplicate()) {
            Documents existing = stage.document();
            return new DocumentInfo(existing.getId(), existing.getDocumentName(), existing.getChunkCount());
        }

        List<String> attemptedVectorIds = new ArrayList<>();
        try {
            int batchSize = Math.max(1, Math.min(embeddingBatchSize, 100));
            int batchCount = (stage.chunks().size() + batchSize - 1) / batchSize;
            for (int offset = 0; offset < stage.chunks().size(); offset += batchSize) {
                checkCancelled(control);
                int end = Math.min(stage.chunks().size(), offset + batchSize);
                List<DocumentChunks> batch = stage.chunks().subList(offset, end);
                List<float[]> vectors = embeddingGateway.embedAll(
                        batch.stream().map(DocumentChunks::getChunkText).toList());
                List<VectorEntry> entries = new ArrayList<>(batch.size());
                for (int index = 0; index < batch.size(); index++) {
                    DocumentChunks chunk = batch.get(index);
                    attemptedVectorIds.add(chunk.getVectorId());
                    entries.add(new VectorEntry(chunk.getVectorId(), vectors.get(index),
                            chunk.getChunkText(), vectorMetadata(descriptor, chunk)));
                }
                vectorStore.upsert(entries);
                control.progress("INDEXING", 2 + (offset / batchSize) + 1, batchCount + 3);
            }

            checkCancelled(control);
            ActivationResult activated = persistence.activate(stage);
            control.progress("COMPLETED", batchCount + 3, batchCount + 3);
            cleanupStaleVectors(descriptor.spaceId(), stage.document().getId(), activated.staleVectorIds());
            refreshVectorMetadata();
            log.info("Document indexed: spaceId={}, documentId={}, version={}, chunks={}",
                    descriptor.spaceId(), stage.document().getId(), stage.version().getVersionNumber(),
                    stage.chunks().size());
            return activated.document();
        } catch (Exception error) {
            boolean cancelled = error instanceof IngestionCancelledException;
            compensateFailedIndex(descriptor.spaceId(), stage, attemptedVectorIds, error, cancelled);
            if (cancelled) {
                throw (IngestionCancelledException) error;
            }
            if (error instanceof IOException ioError) {
                throw ioError;
            }
            throw new IOException("Document indexing failed", error);
        }
    }

    private void compensateFailedIndex(long spaceId, IndexStage stage,
                                       List<String> attemptedVectorIds, Exception indexingError,
                                       boolean cancelled) {
        try {
            vectorStore.delete(attemptedVectorIds);
        } catch (Exception cleanupError) {
            persistence.enqueueDeletes(spaceId, stage.document().getId(), attemptedVectorIds, cleanupError);
            indexingError.addSuppressed(cleanupError);
        } finally {
            persistence.fail(stage, indexingError.getMessage(), cancelled);
        }
    }

    private void checkCancelled(IngestionControl control) {
        if (control != null && control.isCancellationRequested()) {
            throw new IngestionCancelledException();
        }
    }

    private void cleanupStaleVectors(long spaceId, long documentId, List<String> staleIds) {
        try {
            vectorStore.delete(staleIds);
        } catch (Exception error) {
            persistence.enqueueDeletes(spaceId, documentId, staleIds, error);
            log.warn("Stale vector cleanup queued: spaceId={}, documentId={}, count={}",
                    spaceId, documentId, staleIds.size());
        }
    }

    private void refreshVectorMetadata() {
        try {
            vectorIndexManager.refreshEntryCount();
        } catch (RuntimeException error) {
            log.warn("Vector index metadata refresh deferred: errorType={}",
                    error.getClass().getSimpleName());
        }
    }

    private Map<String, Object> vectorMetadata(DocumentDescriptor descriptor, DocumentChunks chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("spaceId", descriptor.spaceId());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("documentVersion", chunk.getDocumentVersion());
        metadata.put("chunkId", chunk.getId());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("documentName", descriptor.documentName());
        metadata.put("source", descriptor.source());
        if (chunk.getSectionTitle() != null) {
            metadata.put("section", chunk.getSectionTitle());
        }
        if (chunk.getPageNumber() != null) {
            metadata.put("page", chunk.getPageNumber());
        }
        return metadata;
    }

    private List<ChunkDraft> split(Document document) {
        String fullText = document.text() == null ? "" : document.text();
        if (fullText.isBlank()) {
            return List.of();
        }
        DocumentSplitter splitter = DocumentSplitters.recursive(
                MAX_SEGMENT_TOKENS, SEGMENT_OVERLAP_TOKENS, tokenEstimator);
        List<TextSegment> segments = splitter.split(document);
        List<ChunkDraft> drafts = new ArrayList<>(segments.size());
        int cursor = 0;
        for (int index = 0; index < segments.size(); index++) {
            TextSegment segment = segments.get(index);
            String text = segment.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            int start = fullText.indexOf(text, Math.max(0, cursor - 256));
            if (start < 0) {
                start = cursor;
            }
            int end = Math.min(fullText.length(), start + text.length());
            cursor = end;
            drafts.add(new ChunkDraft(
                    drafts.size(), text, Hashing.sha256(text), inferSection(text),
                    metadataInteger(segment, "page_number", "pageNumber", "page"),
                    start, end, tokenEstimator.estimateTokenCountInText(text)));
        }
        return drafts;
    }

    private void validateExtractedText(Document document) {
        String text = document == null ? null : document.text();
        if (text != null && text.length() > Math.max(1, maxExtractedCharacters)) {
            throw new IllegalArgumentException("Extracted document text exceeds the configured limit");
        }
    }

    private Integer metadataInteger(TextSegment segment, String... keys) {
        for (String key : keys) {
            if (segment.metadata().containsKey(key)) {
                try {
                    return segment.metadata().getInteger(key);
                } catch (RuntimeException ignored) {
                    // Try the next compatible metadata key.
                }
            }
        }
        return null;
    }

    private String inferSection(String text) {
        for (String line : text.split("\\R", 8)) {
            String value = line.trim();
            if (value.matches("^#{1,6}\\s+.+")) {
                return value.replaceFirst("^#{1,6}\\s+", "");
            }
            if (value.startsWith("===") && value.endsWith("===")) {
                return value.replace("=", "").trim();
            }
        }
        return null;
    }

    private Document parseDocument(String fileName, InputStream inputStream) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser().parse(inputStream);
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv")
                || lower.endsWith(".json") || lower.endsWith(".xml")) {
            return new TextDocumentParser().parse(inputStream);
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcel(inputStream);
        }
        if (lower.endsWith(".docx")) {
            return parseWord(inputStream);
        }
        if (lower.endsWith(".pptx")) {
            return parsePowerPoint(inputStream);
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return parseHtml(inputStream);
        }
        throw new IllegalArgumentException("Unsupported document type: " + extension(fileName));
    }

    private Document parseExcel(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (Sheet sheet : workbook) {
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }
                text.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    for (int column = 0; column < row.getLastCellNum(); column++) {
                        Cell cell = row.getCell(column);
                        if (cell != null) {
                            appendCell(text, cell, dateFormat);
                        }
                        if (column < row.getLastCellNum() - 1) {
                            text.append(" | ");
                        }
                    }
                    text.append('\n');
                }
                text.append('\n');
            }
        }
        return Document.from(text.toString());
    }

    private void appendCell(StringBuilder text, Cell cell, SimpleDateFormat dateFormat) {
        switch (cell.getCellType()) {
            case STRING -> text.append(cell.getStringCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    text.append(dateFormat.format(cell.getDateCellValue()));
                } else {
                    double value = cell.getNumericCellValue();
                    text.append(value == Math.floor(value) ? (long) value : value);
                }
            }
            case BOOLEAN -> text.append(cell.getBooleanCellValue());
            case FORMULA -> text.append(cell.getCellFormula());
            default -> text.append(' ');
        }
    }

    private Document parseWord(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append('\n'));
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                row.getTableCells().forEach(cell -> text.append(cell.getText()).append(" | "));
                text.append('\n');
            }));
        }
        return Document.from(text.toString());
    }

    private Document parsePowerPoint(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XMLSlideShow presentation = new XMLSlideShow(inputStream)) {
            for (int index = 0; index < presentation.getSlides().size(); index++) {
                text.append("=== Slide ").append(index + 1).append(" ===\n");
                presentation.getSlides().get(index).getShapes().forEach(shape -> {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        text.append(textShape.getText()).append('\n');
                    }
                });
            }
        }
        return Document.from(text.toString());
    }

    private Document parseHtml(InputStream inputStream) throws IOException {
        org.jsoup.nodes.Document html = Jsoup.parse(inputStream, "UTF-8", "");
        html.select("script, style, nav, footer, header").remove();
        return Document.from(html.body().text());
    }

    public List<DocumentInfo> getIngestedDocuments() {
        return getIngestedDocuments(KnowledgeSpaceService.DEFAULT_SPACE_ID);
    }

    public List<DocumentInfo> getIngestedDocuments(long spaceId) {
        List<DocumentInfo> rows = documentsMapper.selectReadyDocumentInfo(spaceId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Knowledge space does not exist: " + spaceId);
        }
        return rows.get(0).getId() == null ? List.of() : rows;
    }

    public List<ChunkPreview> listChunkPreviews(Long documentId, int limit) {
        return listChunkPreviews(KnowledgeSpaceService.DEFAULT_SPACE_ID, documentId, limit);
    }

    public List<ChunkPreview> listChunkPreviews(long spaceId, Long documentId, int limit) {
        return listChunkPreviews(spaceId, documentId, 0, limit);
    }

    public List<ChunkPreview> listChunkPreviews(long spaceId, Long documentId,
                                                int offset, int limit) {
        int capped = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(0, offset);
        Documents document = documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getSpaceId, spaceId)
                .eq(Documents::getId, documentId)
                .eq(Documents::getStatus, "READY"));
        if (document == null) {
            throw new IllegalArgumentException("Document does not exist in this knowledge space");
        }
        return chunksMapper.selectList(Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getSpaceId, spaceId)
                        .eq(DocumentChunks::getDocumentId, documentId)
                        .eq(DocumentChunks::getDocumentVersion, document.getCurrentVersion())
                        .eq(DocumentChunks::getStatus, "READY")
                        .orderByAsc(DocumentChunks::getChunkIndex)
                        .last("LIMIT " + capped + " OFFSET " + safeOffset))
                .stream().map(chunk -> {
                    String value = chunk.getChunkText() == null ? "" : chunk.getChunkText();
                    String preview = value.length() > PREVIEW_MAX_CHARS
                            ? value.substring(0, PREVIEW_MAX_CHARS) + "..." : value;
                    return new ChunkPreview(chunk.getId(), chunk.getChunkIndex(), preview,
                            value.length(), chunk.getSectionTitle(), chunk.getPageNumber(),
                            chunk.getStartOffset(), chunk.getEndOffset());
                }).toList();
    }

    public ChunkDetail getChunkDetail(long spaceId, long documentId, long chunkId) {
        Documents document = documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getSpaceId, spaceId)
                .eq(Documents::getId, documentId)
                .eq(Documents::getStatus, "READY"));
        if (document == null) {
            throw new IllegalArgumentException("Document does not exist in this knowledge space");
        }
        DocumentChunks chunk = chunksMapper.selectOne(Wrappers.<DocumentChunks>lambdaQuery()
                .eq(DocumentChunks::getId, chunkId)
                .eq(DocumentChunks::getSpaceId, spaceId)
                .eq(DocumentChunks::getDocumentId, documentId)
                .eq(DocumentChunks::getDocumentVersion, document.getCurrentVersion())
                .eq(DocumentChunks::getStatus, "READY"));
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk does not exist in the active document version");
        }
        String text = chunk.getChunkText() == null ? "" : chunk.getChunkText();
        return new ChunkDetail(chunk.getId(), chunk.getChunkIndex(), text, text.length(),
                chunk.getSectionTitle(), chunk.getPageNumber(), chunk.getStartOffset(),
                chunk.getEndOffset());
    }

    public void deleteDocument(Long documentId) {
        deleteDocument(KnowledgeSpaceService.DEFAULT_SPACE_ID, documentId);
    }

    public void deleteDocument(long spaceId, Long documentId) {
        List<String> vectorIds = persistence.beginDelete(spaceId, documentId);
        try {
            vectorStore.delete(vectorIds);
            persistence.completeDelete(spaceId, documentId);
            refreshVectorMetadata();
        } catch (Exception error) {
            persistence.failDelete(spaceId, documentId, vectorIds, error);
            throw new IllegalStateException("Document vector cleanup failed and was queued for retry", error);
        }
    }

    private static boolean isSupported(String fileName) {
        String ext = extension(fileName);
        return List.of("txt", "md", "csv", "json", "xml", "pdf", "xlsx", "xls",
                "docx", "pptx", "html", "htm").contains(ext);
    }

    private static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "text" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Document name is required");
        }
        String value = Path.of(fileName).getFileName().toString().trim();
        if (value.isEmpty() || value.length() > 255 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid document name");
        }
        return value;
    }
}
