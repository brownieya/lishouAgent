package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.AccountService;
import com.brownie.lishouagent.service.IngestTaskService;
import com.brownie.lishouagent.service.KnowledgeImportService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeImportService importer;
    private final IngestTaskService tasks;
    private final AccountService accounts;

    public KnowledgeController(KnowledgeImportService importer, IngestTaskService tasks, AccountService accounts) {
        this.importer = importer;
        this.tasks = tasks;
        this.accounts = accounts;
    }

    @PostMapping("/import/local")
    public ResponseEntity<?> importLocal() throws Exception {
        return ResponseEntity.ok(importer.importLocal());
    }

    // 用户显式提交导入后立即返回任务 ID，后续通过查询接口读取后台执行结果。
    @PostMapping("/import/local/tasks")
    public ResponseEntity<IngestTaskService.Task> startLocalImport(Principal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(tasks.startLocalImport(accounts.idFor(principal.getName())));
    }

    @GetMapping("/tasks/{id}")
    public IngestTaskService.Task task(@PathVariable UUID id) {
        return tasks.get(id);
    }

    @PostMapping("/tasks/{id}/retry")
    public ResponseEntity<IngestTaskService.Task> retry(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(tasks.retryLocalImport(id));
    }
}
