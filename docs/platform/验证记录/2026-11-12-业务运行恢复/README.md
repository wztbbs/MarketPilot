# 2026-11-12 业务运行恢复故障验证记录

这是对已经完成的本地验证所做的历史归档。归档操作没有重新执行 kill、启动 MySQL 或调用外部系统。

## 已确认的执行结果

依据原始 JUnit 报告：`BusinessRunMySqlIT` 执行 3 项，失败 0、错误 0、跳过 0，套件耗时 **13.307 秒**。该时间不包含前面的 Maven 编译和 MySQL 容器启动；完整流程耗时当时未采集。

| 用例 | 故障注入过程 | 恢复方式及通过的断言 | 用例耗时 |
| --- | --- | --- | --- |
| acceptanceBeforeLocalJobIdPersistenceRecoversByOriginalInvocationId | 本地模拟服务先保存外部任务、阻塞提交响应；父测试确认调用为 EXECUTING、本地没有 jobId、检查点为 WAITING_EXPORT，再强杀 Worker JVM | 新 JVM 以原 invocationId 查单，取得原任务；规划、提交和外部效果各 1 次，业务结果 1 条 | 4.136 秒 |
| crashAfterBusinessCommitRetriesPublicationWithoutDuplicateBusinessEffect | 父测试确认业务结果已提交 1 条，但 businessCommitted 标记仍未保存，再强杀 Worker JVM | 新 JVM 重投同一结果，业务提交尝试共 2 次、结果仍为 1 条；不重新规划或提交外部动作 | 3.982 秒 |
| killedWhileWaitingResumesOriginalPlanWithoutCallback | 父测试确认运行处于 WAITING 且 jobId 已保存，再强杀 Worker JVM；期间让模拟外部任务完成 | 新 JVM 不依赖回调，轮询原 jobId 并恢复；规划、提交和外部效果各 1 次，业务结果 1 条，原请求时间保持一致 | 3.517 秒 |

表中的状态和次数来自“测试通过 + 当时测试代码的断言”，不表示当时保留了数据库原始快照。用例与原断言可在 `source-at-archive-time.zip` 内的 `BusinessRunMySqlIT.java` 中查看。

## 保留的证据

- [JUnit 汇总](reports/mysql-junit.txt)：原报告直接复制。
- [JUnit 用例报告](reports/mysql-junit.xml)：保留用例名、耗时和状态；去掉全部 JVM 系统属性及非结构化输出，避免归档无关环境信息。
- [执行脚本结论](reports/runner-summary.txt)：从原脚本日志提取的开始／通过标记。
- [归档清单](manifest.json)：原 XML 哈希、日志原文件时间、缺失证据、归档时 Git 状态及源码文件 SHA-256。
- `processes/`：保留现存 12 份子进程日志，其中包括两轮执行。早一轮的同轮 JUnit 已被后续执行覆盖，因此本记录只以当前保留的 JUnit 为验收依据，不额外计算通过数量。
- `source-at-archive-time.zip`：归档时的底座 Java 源码、测试源码、pom、表结构和执行脚本快照。执行时没有保存源码指纹，因此该文件明确标记为“归档时快照”，不能声称它是完整、精确的执行时构建版本。
- `SHA256SUMS`：上述归档文件的完整性校验。

最近一轮子进程日志的关联如下。关联依据是 JUnit 用例顺序、日志内容和文件修改时间；当时日志没有独立的用例关联字段。

| 场景 | runId | 原进程日志 | 恢复进程日志 |
| --- | --- | --- | --- |
| 已受理但 jobId 未保存 | d9f6a5a6-418d-4c0d-9f7a-3c77f265c43e | processes/start-650f9a26-415f-4bd3-a401-d72e49b627a5.log（为空，受理响应仍阻塞时进程已退出） | processes/resume-6d9d1561-4496-4b56-8f5a-808c039345ee.log |
| 业务已提交但标记未保存 | f551291f-773f-4c38-a747-8d3501d295ec | processes/start-a0d4166c-ac6e-4bed-bb1e-9ea453590e11.log（含 BUSINESS_COMMITTED） | processes/resume-ee2e8949-1545-4d0a-8c63-927d86d58fb6.log |
| 等待中进程退出 | 6e0af1a7-cddd-4555-a192-aa0fee63e853 | processes/start-bcb313d0-ec38-459b-89a8-8d367726632a.log（含 WAITING） | processes/resume-5f00dbf1-830f-49ae-8fe8-96a1a548fb29.log |

最近一轮日志的原文件修改时间约为北京时间 17:04:00～17:04:12。文件时间用于识别轮次，不能当作准确的 kill 时间。三个恢复日志均包含对应 runId 的 `PUBLISHED ... SUCCEEDED`。

## 当时未记录、无法补造的内容

- 被强杀 JVM 的 PID、具体 kill 时刻和退出码。
- 每个步骤的完整事件时间线。
- 强杀前、强杀后、恢复后的数据库原始快照；临时 MySQL 与凭证已在验证结束后清理。
- MySQL 精确补丁版本和镜像摘要；测试代码确认的是实际 MySQL 8。
- 执行时源码 SHA-256 与完整端到端耗时。

这些字段没有根据现在的状态倒推或补填。现有记录能证明当时的用例及断言通过，但不是一份完整的故障现场审计日志。

## 验证边界

- 被强杀的是装配真实 RunCoordinator、ToolGateway、RunReconciler 的测试 Worker JVM，不是完整生产 Spring Boot 应用。MySQL 和独立模拟服务在 Worker 重启期间继续运行。
- 后两个用例将恢复 JVM 的 Clock 推进 120 秒以覆盖租约过期，不是实际等待一分钟或一小时。kill、数据库写入、外部受理和新 JVM 启动都真实发生。
- 外部任务服务、业务数据和结果提交均为本地测试示例；不调用真实模型、交易所、线上接口或邮件服务。
- Query／Monitor 正式迁移、多实例竞争、MySQL 自身重启和生产灰度不在这三项验证之内。

## 后续记录方式

`scripts/verify_run_contract.py` 已补充自动归档，未来明确重跑时会创建独立目录：

```text
importants/回归测试/运行恢复/<UTC时间>-<随机ID>/
├── manifest.json            源码指纹、镜像 ID、执行阶段与整体耗时、清理结果
├── source-before-run.zip    执行前源码快照，支持未提交工作区
├── runner.log               编译与测试完整输出（已知测试凭证脱敏）
├── events.jsonl             用例、进程、PID、kill 请求/退出及恢复结果的时间线
├── summary.json             每个故障用例的实际结果
├── *-before_kill.json       故障前的运行、调用、业务结果及计数快照
├── *-after_kill.json        JVM 已退出后的快照
├── *-after_recovery.json    恢复后的快照
├── processes/              本轮子进程输出
└── reports/                本轮脱敏 JUnit 报告
```

每轮单独保存，不覆盖旧结果；该目录位于 target 外，不随 `mvn clean` 删除。运行时产物沿用仓库的 `importants/回归测试/` 约定（不默认纳入 Git）；本次历史记录单独放在 docs 下，便于跟随代码保存。

记录增强本身只做编译和轻量格式校验，没有为补日志而重跑高成本故障用例。下次确实需要验证行为变化时，再按[接入文档](../../业务Agent持久化运行接入.md)执行并产生新的一轮完整证据。
