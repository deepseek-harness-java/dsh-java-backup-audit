package cn.xiaofuge.dsh.com.dsh.backupaudit.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshBackupAuditPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-backup-audit-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshBackupAuditPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ScanDirTool(),
                new FreshnessTool(),
                new SizeTrendTool(),
                new GapReportTool(),
                new StrategyCheckTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("backup-audit-backupaudit-capabilities", 20, """
                ## DSH Backup Auditor Plugin
                - 所有工具只读：遍历目录读元数据（大小/时间戳），不修改、不删除、不移动任何备份文件，不写入备份目录。
                - bk_strategy_check 基于用户自述（copies/media/offsite）评估 3-2-1 策略，输出达标/未达标与建议。
                - 发现 0 字节备份文件或体积骤降时明确提示"该文件不可用/任务可能部分失败"，不要含糊。
                - 用户要求删除旧备份腾空间时，必须拒绝：建议先确认保留策略，由人工按策略清理。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__bk_")) {
                return PluginHookResult.context("DSH Backup Auditor audit: read-only, never delete/move backups.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_BACKUP_AUDIT_ADMIN_URL", "http://127.0.0.1:8115")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String dirJson(Map<String, Object> args) {
        return "{\"dirPath\":\"" + json(str(args, "dirPath")) + "\"}";
    }

    private class ScanDirTool extends AbstractTool {
        @Override public String name() { return "bk_scan_dir"; }
        @Override public String description() { return "只读遍历备份目录（深度 4，最多 500 文件），输出文件清单：相对路径/大小/修改时间/新鲜度（<26h 为 fresh）。用户给出备份目录要求体检时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("dirPath", stringSchema("备份目录绝对路径（支持 ~ 开头）"))
                    .prop("adminBaseUrl", stringSchema("可选，Backup Auditor 后台地址，默认 http://127.0.0.1:8115"))
                    .required("dirPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backup/scan", dirJson(args), args));
        }
    }

    private class FreshnessTool extends AbstractTool {
        @Override public String name() { return "bk_freshness"; }
        @Override public String description() { return "备份新鲜度判定：最新备份距现在 <26h=FRESH，<7 天=STALE（查任务是否静默失败），>7 天=视为备份失效。回答\"备份还新鲜吗\"时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("dirPath", stringSchema("备份目录路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("dirPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backup/freshness", dirJson(args), args));
        }
    }

    private class SizeTrendTool extends AbstractTool {
        @Override public String name() { return "bk_size_trend"; }
        @Override public String description() { return "备份大小按天聚合趋势 + 骤降异常检测（相邻天差 >70% 告警：数据源变空或任务部分失败）。评估备份是否\"看起来在跑但内容缩水\"时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("dirPath", stringSchema("备份目录路径"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("dirPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backup/size-trend", dirJson(args), args));
        }
    }

    private class GapReportTool extends AbstractTool {
        @Override public String name() { return "bk_gap_report"; }
        @Override public String description() { return "备份缺口报告：相邻备份间隔超过 expectedIntervalHours×2 即为缺口（该时段数据可能无备份），另列 0 字节坏文件。做备份完整性巡检时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("dirPath", stringSchema("备份目录路径"))
                    .prop("expectedIntervalHours", stringSchema("预期备份间隔小时数，默认 24"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("dirPath")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String interval = str(args, "expectedIntervalHours");
            String body = "{\"dirPath\":\"" + json(str(args, "dirPath")) + "\",\"expectedIntervalHours\":"
                    + (interval.isBlank() ? "null" : interval) + "}";
            return ok(post("/api/backup/gaps", body, args));
        }
    }

    private class StrategyCheckTool extends AbstractTool {
        @Override public String name() { return "bk_strategy_check"; }
        @Override public String description() { return "3-2-1 策略评估：copies=份数 media=介质列表 offsite=yes/no（用户自述），输出三项达标判定与补强建议。做备份策略评审时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("copiesInfo", stringSchema("声明串，格式：copies=3 media=disk,cloud offsite=yes"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("copiesInfo")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/backup/strategy", "{\"copiesInfo\":\"" + json(str(args, "copiesInfo")) + "\"}", args));
        }
    }
}
