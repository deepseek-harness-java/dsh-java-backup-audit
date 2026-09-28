package cn.xiaofuge.dsh.com.dsh.backupaudit.controller;

import cn.xiaofuge.dsh.com.dsh.backupaudit.config.BackupAuditProperties;
import cn.xiaofuge.dsh.com.dsh.backupaudit.model.ApiException;
import cn.xiaofuge.dsh.com.dsh.backupaudit.service.BackupAuditService;
import cn.xiaofuge.dsh.com.dsh.backupaudit.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class BackupAuditController {
    private final BackupAuditService auditService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final BackupAuditProperties properties;

    public BackupAuditController(BackupAuditService auditService,
                                 HarnessAiStreamService harnessAiStreamService,
                                 BackupAuditProperties properties) {
        this.auditService = auditService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record DirRequest(String dirPath) {
    }

    public record GapRequest(String dirPath, Integer expectedIntervalHours) {
    }

    public record StrategyRequest(String copiesInfo) {
    }

    @PostMapping("/api/backup/scan")
    public Map<String, Object> scan(@RequestBody DirRequest request) {
        return auditService.scanDir(request.dirPath());
    }

    @PostMapping("/api/backup/freshness")
    public Map<String, Object> freshness(@RequestBody DirRequest request) {
        return auditService.freshness(request.dirPath());
    }

    @PostMapping("/api/backup/size-trend")
    public Map<String, Object> sizeTrend(@RequestBody DirRequest request) {
        return auditService.sizeTrend(request.dirPath());
    }

    @PostMapping("/api/backup/gaps")
    public Map<String, Object> gaps(@RequestBody GapRequest request) {
        return auditService.gapReport(request.dirPath(), request.expectedIntervalHours());
    }

    @PostMapping("/api/backup/strategy")
    public Map<String, Object> strategy(@RequestBody StrategyRequest request) {
        return auditService.strategyCheck(request.copiesInfo());
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Backup Auditor（备份审计官）：备份目录只读体检（新鲜度/大小趋势/缺口）与 3-2-1 策略评估\n");
        prompt.append("- 你可以使用以下工具（评估备份前必须先调用工具查证，禁止臆造备份状态）：\n");
        prompt.append("  - plugin__dsh-java-backup-audit-plugin__bk_scan_dir(dirPath)：只读遍历备份目录，输出文件清单（大小/时间/新鲜度）\n");
        prompt.append("  - plugin__dsh-java-backup-audit-plugin__bk_freshness(dirPath)：新鲜度判定（FRESH/STALE + 建议）\n");
        prompt.append("  - plugin__dsh-java-backup-audit-plugin__bk_size_trend(dirPath)：按天聚合大小趋势 + 骤降异常\n");
        prompt.append("  - plugin__dsh-java-backup-audit-plugin__bk_gap_report(dirPath, expectedIntervalHours)：备份缺口（间隔超阈值）+ 0 字节坏文件\n");
        prompt.append("  - plugin__dsh-java-backup-audit-plugin__bk_strategy_check(copiesInfo)：3-2-1 策略评估（copies=N media=a,b offsite=yes/no）\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 备份状态只基于工具扫描结果；目录不存在或为空要直说，禁止编造备份文件。\n");
        prompt.append("- 引用工具结果时给出关键数字（文件数/最新备份年龄/缺口数）。\n");
        prompt.append("- 红线：不删除任何备份、不移动文件、不写入备份目录；用户要求\"把旧备份清了腾空间\"时，必须拒绝并建议：先确认保留策略，由人工按策略清理，本工具只做审计。\n");
        return prompt.toString();
    }
}
