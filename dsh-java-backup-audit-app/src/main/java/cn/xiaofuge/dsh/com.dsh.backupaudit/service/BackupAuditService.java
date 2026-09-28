package cn.xiaofuge.dsh.com.dsh.backupaudit.service;

import cn.xiaofuge.dsh.com.dsh.backupaudit.config.BackupAuditProperties;
import cn.xiaofuge.dsh.com.dsh.backupaudit.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backup Auditor 备份审计官：
 * - 备份目录健康检查（新旧/大小/完整性线索）
 * - 3-2-1 策略评估（3 份副本 / 2 种介质 / 1 份异地）
 * 红线：不删除任何备份、不移动文件、不写入备份目录（只读遍历 + 本机 JSON 报告）。
 */
@Service
public class BackupAuditService {

    private final BackupAuditProperties properties;

    /** 备份新鲜度阈值（小时）。 */
    private static final long FRESH_HOURS = 26;
    private static final long STALE_HOURS = 24 * 7;

    public BackupAuditService(BackupAuditProperties properties) {
        this.properties = properties;
    }

    // ---------- 1. 备份目录扫描 ----------

    public Map<String, Object> scanDir(String dirPath) {
        Path dir = resolveDir(dirPath);
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            walk.filter(Files::isRegularFile).limit(500).forEach(f -> {
                try {
                    BasicFileAttributes attr = Files.readAttributes(f, BasicFileAttributes.class);
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", dir.relativize(f).toString());
                    e.put("sizeBytes", attr.size());
                    e.put("modified", attr.lastModifiedTime().toInstant().toString());
                    long ageHours = ChronoUnit.HOURS.between(attr.lastModifiedTime().toInstant(), Instant.now());
                    e.put("ageHours", ageHours);
                    e.put("fresh", ageHours <= FRESH_HOURS);
                    e.put("ext", extOf(f));
                    files.add(e);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "无法遍历目录: " + e.getMessage());
        }
        long totalSize = files.stream().mapToLong(f -> (Long) f.get("sizeBytes")).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dir", dir.toString());
        result.put("fileCount", files.size());
        result.put("totalSizeBytes", totalSize);
        result.put("freshCount", files.stream().filter(f -> (Boolean) f.get("fresh")).count());
        result.put("files", files.stream().sorted(Comparator.comparing(f -> (Long) ((Map) f).get("ageHours"))).toList());
        return result;
    }

    private Path resolveDir(String dirPath) {
        if (dirPath == null || dirPath.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "dirPath 不能为空");
        }
        Path dir = Paths.get(dirPath.replaceFirst("^~", System.getProperty("user.home")));
        if (!Files.isDirectory(dir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "目录不存在: " + dir);
        }
        return dir;
    }

    private String extOf(Path f) {
        String name = f.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx < 0 ? "" : name.substring(idx + 1).toLowerCase();
    }

    // ---------- 2. 新鲜度评估 ----------

    public Map<String, Object> freshness(String dirPath) {
        Map<String, Object> scan = scanDir(dirPath);
        List<Map<String, Object>> files = (List<Map<String, Object>>) scan.get("files");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dir", scan.get("dir"));
        if (files.isEmpty()) {
            result.put("verdict", "EMPTY");
            result.put("advice", "目录里没有任何备份文件——先确认备份任务是否真的在跑");
            return result;
        }
        long newestAge = files.stream().mapToLong(f -> (Long) f.get("ageHours")).min().orElse(Long.MAX_VALUE);
        long oldestAge = files.stream().mapToLong(f -> (Long) f.get("ageHours")).max().orElse(0);
        double avgAge = files.stream().mapToLong(f -> (Long) f.get("ageHours")).average().orElse(0);
        String verdict;
        String advice;
        if (newestAge <= FRESH_HOURS) {
            verdict = "FRESH";
            advice = "最新备份 " + newestAge + " 小时前，节奏健康；最老备份 " + (oldestAge / 24) + " 天前（保留策略正常轮转）";
        } else if (newestAge <= STALE_HOURS) {
            verdict = "STALE";
            advice = "最新备份已 " + (newestAge / 24) + " 天未更新——检查备份任务是否静默失败（最常见：磁盘满 / 密码过期 / 上游路径变更）";
        } else {
            verdict = "STALE";
            advice = "最新备份 " + (newestAge / 24) + " 天前，超过 7 天阈值——视为备份失效，立即人工排查备份任务";
        }
        result.put("verdict", verdict);
        result.put("newestAgeHours", newestAge);
        result.put("oldestAgeHours", oldestAge);
        result.put("avgAgeHours", Math.round(avgAge * 10) / 10.0);
        result.put("advice", advice);
        return result;
    }

    // ---------- 3. 大小趋势 ----------

    public Map<String, Object> sizeTrend(String dirPath) {
        Map<String, Object> scan = scanDir(dirPath);
        List<Map<String, Object>> files = (List<Map<String, Object>>) scan.get("files");
        // 按天聚合
        Map<String, Long> byDay = new TreeMap<>();
        for (Map<String, Object> f : files) {
            String day = String.valueOf(f.get("modified")).substring(0, 10);
            byDay.merge(day, (Long) f.get("sizeBytes"), Long::sum);
        }
        List<Map<String, Object>> trend = byDay.entrySet().stream()
                .map(e -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("day", e.getKey());
                    t.put("sizeBytes", e.getValue());
                    return t;
                })
                .toList();
        // 骤降检测：相邻两天差 > 70%
        List<Map<String, Object>> anomalies = new ArrayList<>();
        List<Map<String, Object>> sorted = new ArrayList<>(trend);
        for (int i = 1; i < sorted.size(); i++) {
            long prev = (Long) sorted.get(i - 1).get("sizeBytes");
            long cur = (Long) sorted.get(i).get("sizeBytes");
            if (prev > 0 && cur < prev * 0.3) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("day", sorted.get(i).get("day"));
                a.put("from", prev);
                a.put("to", cur);
                a.put("advice", "备份体积骤降 " + Math.round((1 - cur * 100.0 / prev)) + "%：可能是数据源变空、压缩率变化或备份任务部分失败，人工核对");
                anomalies.add(a);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dir", scan.get("dir"));
        result.put("totalSizeBytes", scan.get("totalSizeBytes"));
        result.put("dayCount", trend.size());
        result.put("trend", trend);
        result.put("anomalies", anomalies);
        return result;
    }

    // ---------- 4. 缺口报告 ----------

    public Map<String, Object> gapReport(String dirPath, Integer expectedIntervalHours) {
        int interval = expectedIntervalHours == null || expectedIntervalHours <= 0 ? 24 : expectedIntervalHours;
        Map<String, Object> scan = scanDir(dirPath);
        List<Map<String, Object>> files = (List<Map<String, Object>>) scan.get("files");
        List<Long> ages = files.stream().map(f -> (Long) f.get("ageHours")).sorted().toList();
        List<Map<String, Object>> gaps = new ArrayList<>();
        // 相邻备份间隔超阈值 = 缺口
        for (int i = 0; i + 1 < ages.size(); i++) {
            long newer = ages.get(i);
            long older = ages.get(i + 1);
            long holeHours = older - newer;
            if (holeHours > interval * 2) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("holeHours", holeHours);
                g.put("newerAgeHours", newer);
                g.put("olderAgeHours", older);
                g.put("advice", "两次备份间隔 " + (holeHours / 24) + " 天（阈值 " + interval + "h×2）：这段时间的数据可能无备份覆盖，确认是否有意停备");
                gaps.add(g);
            }
        }
        // 完整性线索：0 字节文件
        List<Map<String, Object>> zeroFiles = files.stream()
                .filter(f -> (Long) f.get("sizeBytes") == 0)
                .map(f -> {
                    Map<String, Object> z = new LinkedHashMap<>();
                    z.put("name", f.get("name"));
                    z.put("advice", "0 字节备份文件 = 备份任务写到一半失败，该文件不可用");
                    return z;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dir", scan.get("dir"));
        result.put("intervalHours", interval);
        result.put("fileCount", files.size());
        result.put("gapCount", gaps.size());
        result.put("gaps", gaps);
        result.put("zeroByteCount", zeroFiles.size());
        result.put("zeroByteFiles", zeroFiles);
        result.put("note", "完整性线索仅限元数据（大小/时间戳）；内容级校验需 checksum，由人工抽验");
        return result;
    }

    // ---------- 5. 3-2-1 策略检查 ----------

    public Map<String, Object> strategyCheck(String copiesInfo) {
        // copiesInfo 格式（用户自述）：copies=3 media=disk,cloud offsite=yes
        Map<String, String> info = parseInfo(copiesInfo);
        int copies = Integer.parseInt(info.getOrDefault("copies", "0"));
        List<String> media = info.containsKey("media")
                ? Arrays.stream(info.get("media").split("[,，]")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList()
                : List.of();
        boolean offsite = "yes".equalsIgnoreCase(info.getOrDefault("offsite", "no")) || "true".equalsIgnoreCase(info.getOrDefault("offsite", "no"));

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("3 份副本", copies >= 3,
                copies >= 3 ? "声明 " + copies + " 份，达标" : "仅 " + copies + " 份：主盘损坏即数据全失，至少再加 1 份"));
        checks.add(check("2 种介质", media.size() >= 2,
                media.size() >= 2 ? "介质：" + String.join(" + ", media) : "只有 " + (media.isEmpty() ? "1 种（未声明）" : media.get(0)) + "：同盘双拷贝挡不住 ransomware，加一种不同介质（云/NAS/磁带）"));
        checks.add(check("1 份异地", offsite,
                offsite ? "有异地副本，达标" : "全部副本同址：火灾/进水/勒索软件一锅端，至少放一份到云端或另一机房"));
        long passCount = checks.stream().filter(c -> (Boolean) c.get("pass")).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("declaredCopies", copies);
        result.put("declaredMedia", media);
        result.put("offsite", offsite);
        result.put("passCount", passCount);
        result.put("totalChecks", 3);
        result.put("verdict", passCount == 3 ? "COMPLIANT（3-2-1 达标）" : "NON-COMPLIANT（" + (3 - passCount) + " 项未达标）");
        result.put("checks", checks);
        return result;
    }

    private Map<String, String> parseInfo(String copiesInfo) {
        Map<String, String> info = new LinkedHashMap<>();
        if (copiesInfo == null || copiesInfo.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "copiesInfo 不能为空，格式例：copies=3 media=disk,cloud offsite=yes");
        }
        for (String token : copiesInfo.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) info.put(token.substring(0, eq).toLowerCase(), token.substring(eq + 1));
        }
        if (!info.containsKey("copies")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 copies=N（如 copies=3）");
        }
        try {
            Integer.parseInt(info.get("copies"));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "copies 必须是数字");
        }
        return info;
    }

    private Map<String, Object> check(String name, boolean pass, String advice) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("item", name);
        c.put("pass", pass);
        c.put("advice", advice);
        return c;
    }
}
