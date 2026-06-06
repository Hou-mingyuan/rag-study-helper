package com.rag.studyhelper.controller;

import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.service.DocumentIngestionService;
import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @PostMapping("/upload")
    public Results<DocumentInfo> upload(@RequestParam("file") MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (file.isEmpty() || originalName == null) {
            return Results.failed("文件为空");
        }

        try {
            DocumentInfo result = documentIngestionService.ingestDocument(originalName, file.getInputStream());
            return Results.success(result);
        } catch (IOException e) {
            return Results.failed("上传失败: " + e.getMessage());
        }
    }

    @GetMapping
    public Results<List<DocumentInfo>> listDocuments() {
        return Results.success(documentIngestionService.getIngestedDocuments());
    }

    @PostMapping("/scan")
    public Results<List<DocumentInfo>> scanDocuments() {
        return Results.success(documentIngestionService.scanAndIngest());
    }

    @DeleteMapping("/{id}")
    public Results<Void> deleteDocument(@PathVariable Long id) {
        documentIngestionService.deleteDocument(id);
        return Results.success("删除成功");
    }
}
