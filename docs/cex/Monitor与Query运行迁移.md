# Monitor 与 Query 接入持久化运行

2026-11-12 按 Monitor → Query 的顺序接入 `AgentRunAdapter`。本次复用已有三张底座运行表，不新增表或迁移 SQL；新增的工具定义仍通过 YAML 管理。代码迁移与本地验证不等于线上发布和真实模型验收。

## 入口与归属

```text
AgentDispatcher
  → RunCoordinator（输入快照、租约、累计预算、等待、结果及业务提交）
    → MonitorRunAdapter → PlanEngine.drive → MonitorPlanHooks
    → QueryRunAdapter   → PlanEngine → QueryPlanHooks.planSelfDriven → DagRuntime / DagPlanner
```

Run 是一次可持久化执行，Query TaskVersion 是一次任务状态提交，Monitor RuleVersion 是待确认或已启用的规则定义版本。三者有各自身份，不能互相替代。

Query 的双模式规划修复、历史事实绑定、single/adaptive 执行仍归专用运行时。没有把 Query 改成声明式 Agent，也没有删除 `planSelfDriven`。Monitor 仍使用公共规划循环。后台监控的周期排期、Execution/Work 租约、节点新鲜度检查、连续命中和通知 outbox 继续由业务管理，没有再创建一套竞争同一排期的通用 Run。

## Monitor

1. 冻结原问题、访客、会话轮次、请求时间及版本，进入持久化 Run。
2. 继续原来的模型规划与校验；接受候选后保存 `MONITOR_ACCEPTED`，包含候选、目录版本和用量。
3. 由程序组装确定参数，调用 `monitor.rule.create_draft`。模型不能决定主体、来源轮次或目录版本。工具绑定和 `MonitorDraftAccessPolicy` 检查 Agent、运行、访客、会话及来源轮次归属。
4. 草稿工具是 `OPERATION`，但 `confirmation_required: false`：它只保存未启用的草稿，真正启用仍由原有规则确认完成。没有新增第二次“批准建草稿”的交互，也不会因工具执行成功而启用规则。
5. 草稿保存按来源轮次幂等。同一轮草稿已写入但响应丢失时，按原参数重试返回原版本；旧运行尚未保存草稿且已有更新轮次时，拒绝覆盖新候选。
6. 保存 `MONITOR_COMPLETE` 和业务结果。恢复从候选或完成检查点继续，不重新调用模型生成另一份规则。

生产规划器脱离持久化运行单独调用时，只产生规划结果，不能绕过工具网关保存草稿。后台固定 DAG 的计算工具携带明确的 Monitor／访客身份与白名单，原业务恢复检查仍然生效。

## Query

`QueryRunAdapter.Input` 只保存 PlanningContext、Router 解析后的问题和原 TurnHandle。输入不序列化 Consumer；首段流式回答和进度回调由协调器临时透传，后台恢复通过运行查询接口交付。

| 检查点 | 保存内容 | 恢复行为 |
| --- | --- | --- |
| `query:planner:<attempt>` | 首轮及修复轮模型响应 | 同一修复位置复用原响应，保留原双客户端状态机 |
| `query:accepted-plan` | 通过对账和绑定后的 PlanOutcome | 保留来源任务、事实绑定与固定物理 DAG，不重新选择任务／Provider |
| `dag:node:<id>` | 已完成节点定义、参数、结果、HTTP 次数 | 完成节点直接复用；未完成工具按原 invocationId 等待或查单 |
| `query:fact:<id>` | 节点对应事实 ID | 不重复保存已完成的事实；写库后退出的窗口通过稳定事实 ID 去重 |
| `QUERY_EXECUTION` | EXECUTE／OBSERVE／ANSWER、累积计划、证据、轮次、计数与缺口 | 从原循环边界继续，接受的续规划不重新生成 |
| `query:observe:<decision>` | 补充规划的模型响应 | 响应已保存但循环检查点未写入时复用原决定 |
| `query:answer` | 回答模型响应 | 不重复调用已完成的回答模型 |
| `QUERY_COMPLETE` | 公共结果与显式编码的 QueryOutput | 最终 Run 结果尚未保存时，可直接完成 |

物理计划由 `MetricDagCodec` 恢复，支持原有区间、序列、快照、官方窗口及 recent_n；恢复不重新展开 LogicalPlan。QueryOutput 携带 TaskScope 与最终 CompiledPlan，业务提交不依赖请求进程内的回调变量。

节点日志只保存已完成结果。指标 Provider 是只读查询，若进程在 HTTP 返回与结果检查点之间退出，恢复可能重新读取该未完成节点；不承诺外部读请求 exactly-once。工具操作则依赖工具自己的幂等／查单协议。模型已提交但响应和用量未知的窗口保持底座的保守预算处理，不能盲目重发收费请求。

指标节点预算由运行现场计入，计算工具预算由网关计入，避免双重扣减；已缓存步骤不再扣预算。参数先规范化再冻结，存储读取保留 BigDecimal 精度，避免 JSON 数值节点类型变化造成伪冲突或精度丢失。

## 会话提交与页面

同步完成继续由 AskService 提交原轮次。等待结果释放原会话占用，不提前创建一个 WAITING TaskVersion。恢复完成后由 `ConversationRunCommitter` 在事务内提交：

- 原 HTTP 仍持有有效轮次租约时，稍后重试提交，让原请求先完成。
- 来源仍是最新轮次时，在锁内取得新 fencing token，调用既有会话提交逻辑，创建一次任务版本或保存 Monitor 上下文。
- 用户已有更新轮次时，只补原历史结果，不抢回活动任务或 Agent 焦点。
- 已完成来源轮次直接返回；业务提交成功但 Run 标记未写入时，再次投递不会再建版本。加锁顺序统一为 state → query，终态在锁内读取。

Trace 的终态更新也防止被迟到的初始 WAITING 覆盖。恢复提交使用已落库模型事件汇总用量；恢复后的费用保留未知，不伪造估算。运行接口只向所属访客公开结果，私有 QueryOutput 不进入公共 JSON。

页面继续轮询已有运行接口。最终结果尚未完成业务提交时显示“正在保存结果”；提交后展示 Monitor 原确认卡，或 Query 澄清表单。刷新历史仍由原会话 API 读取最终结果。

## 本地验证与范围

新增 `MonitorRunMigrationTest`、`QueryRunMigrationTest`，使用真实 H2 SQL、事务、工具网关、运行适配器和 Query 编译／校验／执行代码，模型、行情和邮件均为本地替身。覆盖：

- Monitor 草稿经网关保存，仍需原规则确认才能启用；响应丢失后恢复不重复规划、建版本。
- Query 同步流式回答、异步计算等待一小时后重新装配恢复、adaptive 已接受续规划与事实复用。
- Query 模型响应已保存／计划检查点未保存、事实已写库／事实检查点未保存的中止窗口。
- TaskScope、历史版本候选和多种物理查询形态的编解码；高精度参数冻结与恢复。
- 恢复只提交一次任务版本；旧运行完成不会覆盖后续轮次，迟到 WAITING 不能覆盖终态。

这里的中止窗口通过测试注入 Error 和组件重建模拟，时间通过 Clock 推进；本轮没有执行真实 kill、真实 MySQL 故障测试、收费模型或线上回归。此前通用底座的真实 MySQL／强杀 JVM 记录仍见[已有验证记录](../platform/验证记录/2026-11-12-业务运行恢复/README.md)，不能将其视作本次业务迁移的新增验收证据。

本轮具体命令与测试结果见[迁移本地验证记录](验证记录/2026-11-12-Agent迁移.md)。线上发布、真实模型效果和完整监控投递验收仍需另外执行。
