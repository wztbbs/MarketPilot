# CEX 业务文档

CEX 是当前底座的业务实现，代码位于 `com.cexpilot.business.cex`。它提供一次性 Query 和持续 Monitor，共享指标与行情语义，通过业务适配器使用底座的运行、工具和恢复能力。

## 业务架构与运行

| 文档 | 内容 |
| --- | --- |
| [路由与监控规划](路由与监控规划.md) | 当前入口、两个 Agent、模型调用、草稿工具、返回载荷和会话提交 |
| [Monitor 与 Query 运行接入](Monitor与Query运行迁移.md) | 私有输入／输出、检查点、恢复、任务版本幂等提交与边界 |
| [条件交接与灰度](交接灰度与验收.md) | Query → Monitor 条件来源、跨轮上下文、分组开关和验收范围 |
| [迁移本地验证记录](验证记录/2026-11-12-Agent迁移.md) | 已执行的相关本地测试和前端检查；与旧 MySQL／kill 记录分开 |

## Query

- [Plan 与指标元数据](query/Plan与指标元数据.md)：逻辑计划、目录绑定、物理 DAG、引用和扩展步骤。
- [上下文管理](query/上下文管理.md)：Conversation／Turn／Task／TaskVersion、版本候选与历史事实。
- [查询执行反馈](query/查询执行反馈.md)：single／adaptive、累积计划、预算、停止与恢复。
- [入口改写](query/common-query简化.md)：Router 的独立问题与 Planner 原始输入的分工。
- [结构化澄清](query/结构化澄清.md)：自由文本表单、来源轮次、填写值和接续。

## Monitor 与行情

- [主动监控](monitor/主动监控.md)：未启用草稿、规则确认、固定 DAG、排期、Execution／Work、告警与通知。
- [时间窗口](market/时间窗口.md)：TimeSpec、固定请求时刻、时区、采样端点与覆盖。
- [聚合指标](market/聚合指标.md)：Provider 成品指标、确定性算子、精度、来源与年化口径。
- [现货接入](market/现货接入.md)：现货／永续能力差异、专用代理和产品校验。
- [行情 MCP 接入](market/MCP接入.md)：已结算资金费率的跨进程取数与客户端生命周期。

## 站点、排查和验证

| 主题 | 文档 |
| --- | --- |
| 页面与请求 | [首页与会话导航](application/首页与会话导航.md)、[问答流量来源](application/问答流量来源标记.md) |
| 排查与统计 | [Trace](operations/trace搭建.md)、[LLM 诊断](operations/LLM调用诊断与时间口径.md)、[缓存统计](operations/缓存命中统计接口.md) |
| 测试方法 | [common 回归](testing/common回归测试.md)、[评测体系](testing/评测体系设计.md)、[提示词重放](testing/提示词离线重放.md) |

当前能力以指标目录、实际校验器和 API 为准。Query 的 SUCCEEDED、Run 的 SUCCEEDED、Monitor 的 ACTIVE／Execution DONE 各有含义，不能用其中一种状态代替另一种业务结论。真实模型、交易所与邮件的在线验证遵守仓库 [AGENTS.md](../../AGENTS.md)，整理文档不会自动发起这些请求。

业务入门可先读 [domain](../../domain/README.md)；公共运行契约见[底座文档](../platform/README.md)。
