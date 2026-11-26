# 定时告警增强（v3.6.10-lts）

基于上游 `53df526e2fb08bdf933662acf7eb5ae0f1bd5e3a`，新增社区版自己的定时失败告警策略，不改变原有授权控制。

## 配置与行为

在 **项目管理 → 消息管理 → 消息设置 → 定时告警策略** 配置。仍需在原消息设置里启用失败事件、选择接收人和机器人。

- 接口用例、接口场景、测试计划/计划组分别配置。阈值为 1–100，默认 1。
- 同一项目、告警类型、执行资源与环境分别累计。计划按计划/计划组累计，集合环境沿用原执行配置。
- 只统计完成的定时执行。手动执行、API/Jenkins 触发、手动重跑不参与。接口误报打断失败连续性；计划沿用原有通过阈值判定。
- 成功会打断连续失败，不要求启用成功通知。达到 X 次后默认每轮失败继续通知；可取消勾选，改为持续故障仅提醒一次，恢复后重新累计。
- MySQL 持久化、行锁与执行任务项唯一键处理服务重启、并发和消息重投。同一执行生成多个报告也只计一轮，用已落库的执行报告校准连续性，成功回调晚到也能打断失败；按报告完成时间排序；晚到回调若补足阈值，通知使用最新失败报告。每个计数范围最多保留 100 条结果，超过时连续次数显示上限。
- 保存策略（含阈值/模板修改）产生新版本、重新累计；保存前完成的旧报告不计入新版本。并发编辑冲突需重新加载。
- 未达到阈值只跳过通知，不改变执行结果、后续串行任务或测试计划调度。

## 自定义通知

模板留空时使用新的详细默认文本；可编辑模板并预览。支持这些变量：

| 变量 | 内容 |
| --- | --- |
| `${projectName}` | 项目名称 |
| `${resourceName}` | 场景、用例或计划名称 |
| `${environment}` | 执行环境；计划提示查看集合环境 |
| `${reportName}` | 执行报告名称 |
| `${reportUrl}` | 需要现有权限访问的报告地址 |
| `${failedCount}` | 请求失败数；计划提示查看报告统计 |
| `${consecutiveFailures}` | 连续失败次数（最多 100） |
| `${threshold}` | 配置阈值 |
| `${failureDetails}` | 失败请求及原因摘要 |

示例：

```text
${projectName}：${resourceName}
环境：${environment}
连续失败 ${consecutiveFailures} 次，阈值 ${threshold}
${failureDetails}
详情：${reportUrl}
```

详情来自执行报告快照：请求名称、HTTP 方法、去除查询参数的 URL、响应码、失败断言。响应码和时间断言显示期望/实际数值；其他断言显示名称和类型，详细值留在报告中。正文、请求头、Cookie 不进入通知；URL 中的用户名/密码、查询参数和 fragment 会去除，常见秘密标签会脱敏。每份报告最多展开 20 条、每条最多 5 个断言，摘要最多 5000 字符；单个超过 1 MiB 的结果详情使用基础信息和报告链接。脚本/初始化失败而没有请求记录时明确提示查看报告。

已有机器人和接收人设置继续复用。此策略的失败文本优先于原机器人通用模板；成功及其他消息维持原行为。邮件以转义的纯文本预格式块呈现；自定义 Webhook 收到 `{"text":"渲染后的文本"}` JSON；飞书等使用各自原发送通道。

策略使用安全的单轮变量替换，不支持脚本、环境变量读取或递归插值。未知变量拒绝保存。

## 构建和测试

需要 JDK 21、Maven；前端 Maven 插件使用项目绑定的 Node/pnpm。

```sh
mvn -B -ntp -Drevision=3.6.10-lts -N install
mvn -B -ntp -f backend/pom.xml -Drevision=3.6.10-lts -DskipAntRunForJenkins=true \
  -pl services/test-plan -am package \
  -Dtest=ScheduledAlertServiceTest,ApiFailureDetailServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
mvn -B -ntp -Drevision=3.6.10-lts -Dmaven.test.skip=true -pl frontend package
```

MySQL 集成测试需要 Docker，显式加入 `-Dtest=ScheduledAlertServiceTest,ScheduledAlertMysqlIT,ApiFailureDetailServiceTest`。覆盖实际 DDL、MyBatis 映射、事务、跨进程恢复、并发重复/乱序结果、计数隔离、配置版本冲突和保留上限。`.github/workflows/scheduled-alerts.yml` 执行这些检查并保存测试报告与构建产物，随后构建全新社区版容器，实际执行场景与测试计划的定时任务，核验阈值、持续故障抑制、成功恢复、自定义失败详情及前端资源。前端构建按源码内容缓存，纯后端修复不重复构建相同前端。

## 运行打包

源码编译的是公开核心模块，官方社区镜像保留其自带扩展二进制。该方式沿用已验证的运行路径，未宣称所有官方扩展均可仅凭本仓库源码重建。执行上述构建后，从仓库根目录：

```sh
docker build -f deploy/alerts/Dockerfile -t metersphere-alerts:3.6.10-lts .
```

此 Dockerfile 固定官方社区底座 digest，替换 5 个有变更的核心 JAR 及前端静态资源。新增 Flyway 迁移随 domain JAR 发布。`scheduled_alert_policy/state/result` 使用项目级外键级联清理；无新增外部服务。

上线应走所属环境既有发布流水线，并在升级前备份数据库。DDL 新增表、不修改现有报告数据；撤回旧程序不会自动删除新表。

`deploy.py` 供已有单容器发行版的发布流水线调用。环境在流水线提供 `--base`、`--project`、`--container`、`--commit` 和可选的 `--watchdog-unit`，仓库不保存公司地址或凭据。输入 bundle 包含已验收的 5 个 JAR、前端、Dockerfile 和逐文件 SHA-256 的 `manifest.json`。清单绑定源码提交和官方底座 digest；流水线还应固定整个 bundle 的 SHA-256。

发布器先校验并构建镜像，再停服，将数据及原配置卷冷备份到实例 `backups/alerts-<UTC>/`（0700/0600），备份完成后切换。配置卷显式复用原卷，健康检查后核对全部挂载身份、JAR 与前端字节。失败恢复原 Compose 和程序镜像，并恢复已有健康定时器；数据库保留向前兼容的新表，不自动回退数据库。备份包含业务敏感数据，应只在受控宿主按现有备份策略管理，不能发布到 GitHub。`python3 -m unittest discover -s deploy/alerts -p 'test_deploy.py'` 验证配置卷保护、制品校验及备份失败恢复。

通知去重保证同一轮执行只尝试派发一次；外部通道的成功交付仍沿用原发送器行为，不承诺在进程崩溃或 Webhook 失败时必达。
