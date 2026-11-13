# 业务 Agent 持久化运行接入

本文说明当前业务接入契约。Monitor／Query 对话运行已接入，见[Monitor 与 Query 运行迁移](../cex/Monitor与Query运行迁移.md)；Query 的 `planSelfDriven → DagRuntime` 路径、Monitor 的规则排期及业务状态保持原有归属。

## 现在可以接入什么

`RunCoordinator` 根据 `AgentRunAdapter` 注册关系执行运行，不再限定 Agent 必须是 `DeclaredAgent`。声明式 ReAct／Plan 使用 `DeclaredAgentRunAdapter`；业务 Agent 可以提供自己的分段状态机，并选择如何调用现有引擎。Dispatcher 对没有适配器的 Agent 仍保留直接引擎分发；当前声明式 Agent、Query、Monitor 均已有适配器。

工具绑定也独立为 `ToolBoundAgent.allowedTools()`。声明式和业务 Agent 使用相同的当前绑定检查；业务 Agent 不需要为了使用操作工具而伪装成 YAML 声明式 Agent。未显式绑定操作工具的原内置路径仍只有原只读权限。

## 接入契约

| 契约 | 业务负责什么 |
| --- | --- |
| `AgentRunAdapter.id/version` | 提供稳定身份；输入、检查点、输出或执行语义变化时变更版本 |
| `supports/limits` | 声明适配哪些已注册 Agent、使用哪些累计预算和活跃片段时间上限 |
| `captureInput/restoreInput` | 将业务私有输入显式编码为 JSON，再恢复为业务输入类型 |
| `executeSegment` | 从原输入和检查点继续执行，返回 Completed 或 Waiting |
| `RunSegment.checkpoint` | 在对应外部动作提交之前，保存业务执行阶段及恢复需要的状态 |
| `captureOutput/restoreOutput` | 显式编码和恢复业务私有结果，供任务版本等业务提交使用 |
| `commit` | 最终结果落库后，以 runId 为幂等键提交业务结果；允许重试 |

输入和检查点只保存数据，不保存 Consumer、Runnable、Spring Bean 或 Java 调用栈。每段执行都从输入快照恢复，包括第一段，因此缺失的编解码会在首次执行时暴露。公共 ExecutionResult 保存时移除接口类型的私有 output，私有结果单独保存并由原适配器恢复；不启用任意类名的多态反序列化。

`RunCheckpoint` 由业务阶段名和 JSON 状态构成。底座不会解释阶段内的查询定义、监控规则或其他业务对象，也不会替业务设计状态机。新字段保存在现有运行 JSON 中，本次没有新增生产表。

## 一次分段执行

测试用自驱动文档导出 Agent 使用以下业务阶段：

1. 没有检查点时生成一次计划，把文档 ID、原版本和已接受计划保存为 `WAITING_EXPORT`。
2. 调用统一工具入口。需要确认或外部任务尚未完成时，返回关联原调用 ID 的 `RunStep.Waiting`，释放线程。
3. 恢复时先读取 `WAITING_EXPORT`，使用原计划和确定参数；已有外部任务按原 jobId 查询，结果丢失时按 invocationId 查单。
4. 工具结果完成后保存 `EXPORT_COMPLETE` 和业务私有输出，返回 `RunStep.Completed`。如果在该检查点与最终结果保存之间退出，也可以直接从完成检查点重建结果。
5. 底座保存最终结果，再调用原适配器的 `commit`。业务状态提交成功后，底座记录 `businessCommitted`，最后完成应用投影并记录 `completionPublished`。

Waiting 通过 RunWaitTarget 引用本次运行、本主体的已持久化工具调用或审批方案。片段不能在还有未完成调用或方案时返回 Completed。已有声明式引擎抛出的 `RunSuspended` 仍作为正常控制流，由协调器转成同一个等待协议。

## 业务提交为什么必须幂等

数据库业务提交完成后，进程可能在底座记录提交标记之前退出。重启后必须重新投递同一份最终结果，因此 `commit` 是至少一次投递，业务必须用 runId 唯一键、来源轮次唯一键、业务版本 CAS 或事务内 outbox 去重。

本地示例用 runId 主键保存业务结果；重复投递时校验内容一致，保留一条结果。业务提交不能重新规划或执行工具。返回 false 表示业务入口尚未准备好，抛异常表示暂时提交失败，二者均由到期调度补偿。最终结果已保存后，重试提交不会重新执行运行片段。

适配器版本不匹配时停止恢复；已经完成但尚未提交的结果也需要原编解码协议。线上升级时应保持所需版本可用、排空等待任务或显式迁移检查点，不能静默丢弃私有状态。

## 本地验证

快速测试 `BusinessRunAdapterTest` 使用 H2 与本机 HTTP 模拟服务，覆盖输入／输出编解码、显式阶段、确认、重装配后恢复、版本拒绝及 Dispatcher 通过 SPI 选择业务适配器。

`BusinessRunMySqlIT` 使用真实 MySQL 8、独立的本机 HTTP 任务服务和子 JVM，覆盖：

| 场景 | 故障点 | 验证 |
| --- | --- | --- |
| 等待中进程退出 | 已保存 jobId，强杀业务 JVM | 新 JVM 不接收回调，靠轮询恢复；不重新规划或提交外部操作 |
| 外部已受理但本地未保存 | 模拟服务已保存任务，阻塞提交响应，随后强杀 JVM | 新 JVM 用原 invocationId 查单取得 jobId，不重复提交 |
| 业务已提交但标记未保存 | 业务事务已提交，适配器尚未返回，随后强杀 JVM | 再次投递同一私有输出；两次提交尝试，一条业务结果 |

各场景检查原主体、Agent、确定参数、累计工具预算及业务私有输入／输出；等待场景还检查原请求时间保持一致。测试使用实际的 `destroyForcibly` 退出子 JVM。后两项通过新 JVM 的可控 Clock 推进租约时间，不实际等待一分钟或一小时；数据库写入、受理记录、进程退出和重新启动均真实发生。

示例全部位于 `src/test/java/com/cexpilot/platform/run/fixture/`，不注册生产 Agent，不访问真实 LLM、交易所、线上站点或邮件服务。

## 一键复现

前提：本机 Docker 已启动，已有 `mysql:8.0` 镜像。运行：

```bash
python3 scripts/verify_run_contract.py
```

脚本先执行 `mvn test-compile`，再创建独立临时 MySQL 容器和 `cexpilot_run_contract` 数据库，只绑定 127.0.0.1。凭证随机生成，通过环境传入容器，临时配置文件权限为 0600，不在命令行传密码。仅执行 BusinessRunAdapterTest、BusinessRunMySqlIT；结束后删除自己的容器和临时凭证文件。每轮过程证据独立保存在 `importants/回归测试/运行恢复/<UTC时间>-<随机ID>/`，包含执行前源码与指纹、分阶段耗时、逐步事件、PID／kill 退出码、数据库快照、子进程日志及脱敏报告；不覆盖旧轮次，也不随 mvn clean 删除。

已有独立本地测试库时，也可以自行提供配置文件后执行：

```bash
mvn test-compile
mvn test -Dtest=BusinessRunMySqlIT -Drun.mysql.config=/absolute/path/mysql.properties
```

配置文件字段为 url、username、password。测试强制 URL 为 `jdbc:mysql://127.0.0.1:<端口>/cexpilot_run_contract?...`，检查真实 MySQL 8 版本；不会回退到 H2，也不会接受线上数据库地址。不要将配置文件提交仓库。

JUnit 原报告在 `target/surefire-reports/`；完整归档在 `importants/回归测试/运行恢复/`。直接运行 MySQL 测试时也会在该目录写入 JVM 事件与快照，可用 `-Drun.contract.evidence=<新的独立目录>` 指定路径；默认不采集任意环境变量和凭证。首次已执行轮次的现存证据已整理为[2026-11-12 故障验证记录](验证记录/2026-11-12-业务运行恢复/README.md)，其中明确列出历史缺失字段。这组历史验证证明示例业务运行契约在所列场景下成立；Query／Monitor 的后续代码迁移与本地测试见迁移文档；真实业务回归及线上灰度仍需单独执行。

## 方案审批接入

适配器可通过 toolApprovalMode 显式选择 PREPARED_PLAN；等待审批返回 RunStep.waitingForApproval(planId)，工具等待继续使用 RunStep.waiting(invocationId)。方案与调用归属由协调器校验。准备、确认、执行及前端接口见[方案审批与分阶段执行](方案审批与分阶段执行.md)。默认 PER_CALL 保留现有 Agent 的逐调用确认行为。
