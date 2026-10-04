package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.KnowledgeImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeImportService importer;

    public KnowledgeController(KnowledgeImportService importer) {
        this.importer = importer;
    }

    @PostMapping("/import/local")
    public ResponseEntity<?> importLocal() throws Exception {
        return ResponseEntity.ok(importer.importLocal());
    }
}
