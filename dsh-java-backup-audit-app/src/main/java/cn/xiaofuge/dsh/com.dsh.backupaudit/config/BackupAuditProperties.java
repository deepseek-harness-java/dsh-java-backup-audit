package cn.xiaofuge.dsh.com.dsh.backupaudit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.backupaudit")
public record BackupAuditProperties(
        String dataDirectory,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}
