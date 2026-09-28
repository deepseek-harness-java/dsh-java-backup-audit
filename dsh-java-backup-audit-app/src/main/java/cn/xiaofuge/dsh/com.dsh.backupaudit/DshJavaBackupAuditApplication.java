package cn.xiaofuge.dsh.com.dsh.backupaudit;

import cn.xiaofuge.dsh.com.dsh.backupaudit.config.BackupAuditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BackupAuditProperties.class)
public class DshJavaBackupAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaBackupAuditApplication.class, args);
    }
}
