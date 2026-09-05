package com.rag.studyhelper.controller;

import com.rag.studyhelper.utils.Results;
import com.rag.studyhelper.vector.ReconciliationReport;
import com.rag.studyhelper.vector.VectorIndexManager;
import com.rag.studyhelper.vector.VectorRebuildReport;
import com.rag.studyhelper.vector.VectorReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vector")
public class VectorOperationsController {

    private final VectorIndexManager indexManager;
    private final VectorReconciliationService reconciliation;

    public VectorOperationsController(VectorIndexManager indexManager,
                                      VectorReconciliationService reconciliation) {
        this.indexManager = indexManager;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/status")
    public Results<VectorRebuildReport> status() {
        return Results.success(indexManager.status());
    }

    @PostMapping("/rebuild")
    public Results<VectorRebuildReport> rebuild() {
        return Results.success(indexManager.rebuild());
    }

    @PostMapping("/reconcile")
    public Results<ReconciliationReport> reconcile() {
        return Results.success(reconciliation.reconcile());
    }
}
