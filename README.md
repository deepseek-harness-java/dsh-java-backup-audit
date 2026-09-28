# dsh-java-backup-audit — Backup Auditor（备份审计官）

> DSH Java Native Plugin 独立 AI 产品 #24/30。AI 驱动的备份体检：只读遍历备份目录，判定新鲜度、发现大小骤降与备份缺口、评估 3-2-1 策略。**红线：不删除任何备份、不移动文件、不写入备份目录。**

## 产品定位

"备份看着在跑，恢复时才发现是空的"——备份失效是最贵的安全事故。Backup Auditor 对备份目录做只读体检：最新备份多久了？中间有没有 15 天的空洞？有没有 0 字节坏文件？备份体积是不是骤降了？AI 负责解读与建议，但**绝不动备份文件**。

- App：Spring Boot，端口 `8115`
- Plugin：`dsh-java-backup-audit-plugin`（Java Native，SPI 注册）

## 工具集（5 个）

| 工具 | 说明 |
|---|---|
| `bk_scan_dir` | 只读遍历（深度 4 / 500 文件上限）：相对路径/大小/修改时间/新鲜度（<26h=fresh） |
| `bk_freshness` | 新鲜度判定：FRESH（<26h）/ STALE（>26h 查静默失败，>7 天视为备份失效） |
| `bk_size_trend` | 按天聚合大小趋势 + 骤降异常（相邻天差 >70% 告警：数据源变空/任务部分失败） |
| `bk_gap_report` | 备份缺口：相邻备份间隔 > interval×2 即空洞 + 0 字节坏文件清单 |
| `bk_strategy_check` | 3-2-1 策略评估（用户自述 copies/media/offsite）：三项达标判定 + 补强建议 |

## 安全红线（systemPrompt + PRE_TOOL_USE hook 双保险）

1. 全部工具只读元数据，不修改/删除/移动备份文件，不写入备份目录。
2. 0 字节文件与体积骤降必须明确说"该文件不可用/任务可能部分失败"。
3. 用户要求"把旧备份清了腾空间"时，AI 必须拒绝：建议先确认保留策略，由人工按策略清理。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/backup/scan` | 目录扫描 |
| POST | `/api/backup/freshness` | 新鲜度判定 |
| POST | `/api/backup/size-trend` | 大小趋势 + 骤降异常 |
| POST | `/api/backup/gaps` | 缺口报告（expectedIntervalHours 可选） |
| POST | `/api/backup/strategy` | 3-2-1 策略评估 |
| POST | `/api/assistant/stream` | SSE 对话（转发 harness） |

## E2E

`backup_auditor_e2e.py` — **23 断言全通过**（fixture：`/tmp/bk-demo/full` 含 5 文件 + 1 个 0 字节坏文件 + 15 天缺口；`/tmp/bk-demo/stale` 含 16 天前的过期备份）：

- T0 扫描（数量/坏文件/新鲜计数/相对路径）
- T1/T2 新鲜度（FRESH vs STALE + 失败排查建议）
- T3 趋势（多天聚合 / 0 大小天 / 不存在目录 404）
- T4 缺口（5d→20d 空洞 / 0 字节标记）
- T5 策略（全达标 / 全不达标 / 缺 copies 400）
- T6 SSE AI 经插件工具查新鲜度（未 shell 绕行）
- T7 SSE AI **拒绝删旧备份**，给保留策略建议
- T8 SSE AI 3-2-1 评估（copies=2 offsite=no → 未达标）

## 运行

```bash
mvn package -DskipTests
java -jar dsh-java-backup-audit-app/target/dsh-java-backup-audit-app-0.1.0-SNAPSHOT.jar --server.port=8115
# 插件安装/激活同其他产品（pluginId: dsh-java-backup-audit-plugin）
```

打开 `http://127.0.0.1:8115`，左侧 AI 对话，右侧工作台：扫描 / 新鲜度 / 趋势 / 缺口。
