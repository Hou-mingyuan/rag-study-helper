package com.rag.demo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.demo.mapper.DocumentChunksMapper;
import com.rag.demo.mapper.DocumentsMapper;
import com.rag.demo.model.DocumentChunks;
import com.rag.demo.model.DocumentInfo;
import com.rag.demo.model.Documents;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentsMapper documentsMapper;

    @Autowired
    private DocumentChunksMapper documentChunksMapper;

    @Value("${app.rag.document-scan-path}")
    private String scanPath;

    @PostConstruct
    public void init() {
        scanAndIngest();
    }

    private String sha256(byte[] input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public List<DocumentInfo> scanAndIngest() {
        log.info("Scanning document directory: {}", scanPath);
        List<DocumentInfo> results = new ArrayList<>();
        File dir = new File(scanPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("Created document directory: {}", scanPath);
            return results;
        }
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
                || n.endsWith(".json") || n.endsWith(".xml")
                || n.endsWith(".pdf")
                || n.endsWith(".xlsx") || n.endsWith(".xls")
                || n.endsWith(".docx")
                || n.endsWith(".pptx")
                || n.endsWith(".html") || n.endsWith(".htm");
        });
        if (files == null || files.length == 0) {
            log.info("No documents found in {}", scanPath);
            return results;
        }
        for (File file : files) {
            try {
                DocumentInfo info = ingestDocument(file.toPath());
                results.add(info);
            } catch (Exception e) {
                log.error("Failed to ingest document: {}", file.getName(), e);
            }
        }
        log.info("Document scan complete. Total ingested: {}", results.size());
        return results;
    }

    public DocumentInfo ingestDocument(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        try (InputStream inputStream = new FileInputStream(filePath.toFile())) {
            return ingestDocument(fileName, inputStream);
        }
    }

    /**
     * 通过输入流直接解析文档入库，无需临时文件。
     */
    public DocumentInfo ingestDocument(String fileName, InputStream inputStream) throws IOException {
        byte[] content = IOUtils.toByteArray(inputStream);
        String hash = sha256(content);
        log.info("Ingesting document: {}, hash={}", fileName, hash);

        Documents existing = documentsMapper.selectOne(
                Wrappers.<Documents>lambdaQuery().eq(Documents::getContentHash, hash)
        );
        if (existing != null) {
            log.info("Document already ingested: {} (hash={})", existing.getDocumentName(), hash);
            return new DocumentInfo(existing.getId(), existing.getDocumentName(), existing.getChunkCount());
        }

        Document document = parseDocument(fileName, new ByteArrayInputStream(content));
        return processAndSave(document, fileName, "UPLOAD", hash, (long) content.length,
                null, null, null, "system");
    }

    /**
     * 直接传入文档文本内容入库。适用于飞书等文本来源。
     */
    public DocumentInfo ingestDocument(String fileName, String content) throws IOException {
        Document document = Document.from(content);
        return processAndSave(document, fileName, null, null, null,
                null, null, null, "system");
    }

    /**
     * 飞书文档入库（携带飞书元数据）。
     */
    public DocumentInfo ingestFeishuDocument(String fileName, String content,
                                              String nodeToken, long updateTime, String objType) throws IOException {
        Document document = Document.from(content);
        return processAndSave(document, fileName, "FEISHU", null, null,
                nodeToken, objType, updateTime, "system");
    }

    private Document parseDocument(String fileName, InputStream inputStream) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser().parse(inputStream);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".csv") || lower.endsWith(".json")
                || lower.endsWith(".xml")) {
            return new TextDocumentParser().parse(inputStream);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcel(inputStream);
        } else if (lower.endsWith(".docx")) {
            return parseWord(inputStream);
        } else if (lower.endsWith(".pptx")) {
            return parsePowerPoint(inputStream);
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return parseHtml(inputStream);
        } else {
            throw new IllegalArgumentException("Unsupported file: " + fileName);
        }
    }

    private DocumentInfo processAndSave(Document document, String fileName, String source,
                                         String contentHash, Long fileSize,
                                         String feishuNodeToken, String feishuObjType,
                                         Long feishuUpdateTime, String creator) throws IOException {
        DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(300, 60);
        List<TextSegment> segments = splitter.split(document);

        segments.replaceAll(textSegment -> TextSegment.from(
                "[来源:" + fileName + "]\n" + textSegment.text()));

//        todo 权限 rag 新增 仅 chroma 和 milvus 使用 如果是 私有文档，使用 .put("document_id", docRecord.getId())))
//        segments.replaceAll(textSegment -> TextSegment.from(
//                "[来源:" + fileName + "]\n" + textSegment.text(),
//                new Metadata()
//                        .put("visibility", "public")));

        List<Embedding> allEmbeddings = new ArrayList<>();
        int batchSize = 10;
        for (int i = 0; i < segments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            try {
                List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
                allEmbeddings.addAll(embeddings);
                log.info("  Embedded batch {}-{}/{}", i, end, segments.size());
            } catch (Exception e) {
                log.warn("  Batch {}-{} failed, trying one-by-one", i, end);
                for (TextSegment seg : batch) {
                    try {
                        allEmbeddings.add(embeddingModel.embed(seg.text()).content());
                    } catch (Exception e2) {
                        log.warn("  Skipping chunk: {}", seg.text().substring(0, Math.min(50, seg.text().length())));
                    }
                }
            }
        }

        int stored = Math.min(allEmbeddings.size(), segments.size());
        List<String> vectorIds = embeddingStore.addAll(allEmbeddings, segments.subList(0, stored));

        String docType = "unknown";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            docType = fileName.substring(dotIdx + 1).toLowerCase();
        }

        Documents docRecord = new Documents();
        docRecord.setDocumentName(fileName);
        docRecord.setDocumentType(docType);
        docRecord.setSource(source);
        docRecord.setContentHash(contentHash);
        docRecord.setFileSize(fileSize != null ? fileSize : 0L);
        docRecord.setChunkCount(stored);
        docRecord.setFeishuNodeToken(feishuNodeToken);
        docRecord.setFeishuObjType(feishuObjType);
        docRecord.setFeishuUpdateTime(feishuUpdateTime);
        docRecord.setCreator(creator);
        documentsMapper.insert(docRecord);

        for (int i = 0; i < stored && i < vectorIds.size(); i++) {
            DocumentChunks chunk = new DocumentChunks();
            chunk.setDocumentId(docRecord.getId());
            chunk.setVectorId(vectorIds.get(i));
            chunk.setChunkIndex(i);
            chunk.setChunkText(segments.get(i).text());
            documentChunksMapper.insert(chunk);
        }

        log.info("Ingested {} with {} chunks, documentId={}", fileName, stored, docRecord.getId());
        return new DocumentInfo(docRecord.getId(), fileName, stored);
    }

    private Document parseExcel(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet.getPhysicalNumberOfRows() == 0) continue;
                text.append("=== 工作表: ").append(sheet.getSheetName()).append(" ===\n");

                for (Row row : sheet) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            switch (cell.getCellType()) {
                                case STRING:
                                    text.append(cell.getStringCellValue());
                                    break;
                                case NUMERIC:
                                    if (DateUtil.isCellDateFormatted(cell)) {
                                        text.append(dateFmt.format(cell.getDateCellValue()));
                                    } else {
                                        double val = cell.getNumericCellValue();
                                        if (val == Math.floor(val) && !Double.isInfinite(val)) {
                                            text.append((long) val);
                                        } else {
                                            text.append(val);
                                        }
                                    }
                                    break;
                                case BOOLEAN:
                                    text.append(cell.getBooleanCellValue());
                                    break;
                                case FORMULA:
                                    try {
                                        String formulaVal = cell.getStringCellValue();
                                        text.append(formulaVal);
                                    } catch (Exception e) {
                                        text.append(cell.getCellFormula());
                                    }
                                    break;
                                default:
                                    text.append(" ");
                            }
                        }
                        if (c < row.getLastCellNum() - 1) {
                            text.append(" | ");
                        }
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        String content = text.toString();
        log.info("  Extracted {} chars from Excel", content.length());
        return Document.from(content);
    }

    private Document parseWord(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                text.append(para.getText()).append("\n");
            }
            for (org.apache.poi.xwpf.usermodel.XWPFTable table : doc.getTables()) {
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        text.append(cell.getText()).append(" | ");
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }
        return Document.from(text.toString());
    }

    private Document parsePowerPoint(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(inputStream)) {
            int slideNum = 1;
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
                text.append("=== 幻灯片 ").append(slideNum++).append(" ===\n");
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        text.append(((org.apache.poi.xslf.usermodel.XSLFTextShape) shape).getText()).append("\n");
                    }
                }
                text.append("\n");
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
        List<Documents> docs = documentsMapper.selectList(
                Wrappers.<Documents>lambdaQuery()
                        .select(Documents::getId, Documents::getDocumentName, Documents::getChunkCount)
                        .orderByDesc(Documents::getCreateTime)
        );
        List<DocumentInfo> result = new ArrayList<>();
        for (Documents doc : docs) {
            result.add(new DocumentInfo(doc.getId(), doc.getDocumentName(), doc.getChunkCount()));
        }
        return result;
    }

    public void deleteDocument(Long documentId) {
        List<DocumentChunks> chunks = documentChunksMapper.selectList(
                Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getDocumentId, documentId)
        );
        for (DocumentChunks chunk : chunks) {
            try {
                embeddingStore.remove(chunk.getVectorId());
            } catch (Exception e) {
                log.warn("Failed to remove vector {}: {}", chunk.getVectorId(), e.getMessage());
            }
        }
        documentChunksMapper.delete(
                Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getDocumentId, documentId)
        );
        documentsMapper.deleteById(documentId);
        log.info("Deleted document id={} with {} chunks", documentId, chunks.size());
    }
}
