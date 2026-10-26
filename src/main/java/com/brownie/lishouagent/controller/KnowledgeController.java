package com.brownie.lishouagent.controller;

import com.brownie.lishouagent.service.AccountService;
import com.brownie.lishouagent.service.IngestTaskService;
import com.brownie.lishouagent.service.KnowledgeImportService;
import com.brownie.lishouagent.service.KnowledgeDocumentParseService;
import com.brownie.lishouagent.service.KnowledgeUploadService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeImportService importer;
    private final IngestTaskService tasks;
    private final AccountService accounts;
    private final KnowledgeUploadService uploads;
    private final KnowledgeDocumentParseService parser;

    public KnowledgeController(KnowledgeImportService importer, IngestTaskService tasks, AccountService accounts,
            KnowledgeUploadService uploads, KnowledgeDocumentParseService parser) {
        this.importer = importer;
        this.tasks = tasks;
        this.accounts = accounts;
        this.uploads = uploads;
        this.parser = parser;
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

    // 用户上传 Markdown/ZIP 时调用：仅保存并登记文件，解析与向量发布由后续任务完成。
    @PostMapping(value = "/uploads", consumes = "multipart/form-data")
    public ResponseEntity<KnowledgeUploadService.Upload> upload(@RequestPart("file") MultipartFile file,
            Principal principal) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.upload(file, accounts.idFor(principal.getName())));
    }

    @GetMapping("/uploads/{id}")
    public KnowledgeUploadService.Upload upload(@PathVariable UUID id) {
        return uploads.get(id);
    }

    // 查询上传解析预览时调用：返回文档结构和图片关联，不触发导入或向量化。
    @GetMapping("/uploads/{id}/parsed")
    public KnowledgeDocumentParseService.ParseResult parsedUpload(@PathVariable UUID id) throws Exception {
        return parser.parse(id);
    }
}
