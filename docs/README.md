# 文档导航

文档按**底座**与 **CEX 业务**组织。先读[当前架构](当前架构.md)，再进入对应目录；专题文档描述当前工作树，验证记录只证明记录中的代码和场景，不代表当前线上状态。

| 入口 | 回答的问题 |
| --- | --- |
| [当前架构](当前架构.md) | 系统怎样组成，两类 Agent 怎样通过底座运行？ |
| [底座文档](platform/README.md) | 如何接入 Agent、控制工具执行、等待和恢复？ |
| [CEX 业务文档](cex/README.md) | Query、Monitor、行情能力与站点功能怎样实现？ |
| [历史设计](archive/README.md) | 早期方案为何这样演进？哪些协议已经停用？ |

## 目录约定

```text
docs/
├── 当前架构.md                 跨底座与业务的系统总览
├── platform/                  公共运行与接入契约
│   └── 验证记录/              底座契约、MySQL／进程故障历史证据
├── cex/                       CEX Agent 组合、路由、交接与运行适配
│   ├── query/                 查询计划、任务上下文、澄清及执行反馈
│   ├── monitor/               规则确认、排期、检查、告警与投递
│   ├── market/                行情、指标计算、时间、现货和行情 MCP
│   ├── application/           首页、会话导航与请求来源
│   ├── operations/            站点 Trace、调用诊断和统计接口
│   ├── testing/               CEX 回归、评测与提示词重放
│   └── 验证记录/              业务接入及迁移的历史验证
└── archive/                   被当前方案替代的设计与协议
```

底座文档可引用 CEX 作为适配实例，公共契约不以 CEX 业务对象为前提。行情 MCP、指标目录、TaskVersion 和 Monitor 规则属于 CEX。通用计算执行器在底座，行情计算口径说明放在 CEX 的 market 中。

新增文档优先补充已有专题。修改执行链、API、模型字段、包结构或持久化状态时，同步更新对应现行文档；不要再用“阶段 1／2／3／4”作为当前架构的分类。历史实验、验收日期与结论保留原意，新增验证单独成记录。原始日志、源码快照及校验文件保持原样。

## 常用阅读路线

- 理解和扩展底座：[底座架构](platform/底座架构.md) → [包边界](platform/底座隔离与演进.md) → [Agent 接入](platform/业务Agent持久化运行接入.md) → [工具与恢复](platform/工具控制与可恢复运行.md)。
- 开发 Query：[CEX 路由](cex/路由与监控规划.md) → [Plan 协议](cex/query/Plan与指标元数据.md) → [任务上下文](cex/query/上下文管理.md) → [执行反馈](cex/query/查询执行反馈.md)。
- 开发 Monitor：[CEX 路由](cex/路由与监控规划.md) → [主动监控](cex/monitor/主动监控.md) → [运行适配](cex/Monitor与Query运行迁移.md)。
- 排查问题：[Trace](cex/operations/trace搭建.md) → [LLM 诊断](cex/operations/LLM调用诊断与时间口径.md) → [离线重放](cex/testing/提示词离线重放.md)。

业务入门材料仍在 [domain](../domain/README.md)，脚本用法与实验产物分别保留在 `scripts/` 和 `importants/`。调整文档不触发线上回归、收费模型调用或故障演练。
