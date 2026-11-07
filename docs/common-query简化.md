# Common query：入口改写与辅助协议简化

## 调用链

正常查询仍是三次模型调用：Router（意图＋独立查询改写）→ Planner → 执行工具 → Answer。不增加单独的 rewrite LLM 调用。

Router 的 query 输出为 `{"route":"query","resolved_question":"独立问题"}`。已有独立问题保持原意；追问补齐有效上下文，缺失信息保留缺口，不编造期数、交易所或比较口径。monitor 与 clarify 路由保留原协议，不生成 query 改写。

缺失、空白、类型错误或超长的 resolved_question 回退用户原问题，不为改写本身重试。路由分类 JSON 本身不合法时仍保留原有路由修复流程。

`QUERY_REWRITE` 事件记录 original_question、resolved_question、changed。原始问题仍用于 ask_trace、会话历史和 Answer；独立查询只作为 Planner 的本轮问题。trace 页面在发生改写时展示“规划使用的问题”。

## 精简上下文

Router 获取当前查询有效条件、已填写澄清值、未完成需求及任务索引摘要；不加载指标目录和完整历史事实目录。Planner 的上下文删除重复展开的字段来源链（turn/frame/version/requirement 来源 JSON），只保留可选参数是否为用户固定要求的摘要。完整来源仍由程序持久化，用于继承处理，不删除数据库状态。

历史事实候选和短别名、历史任务/版本、固定范围及对应请求时间保留。监控→单次查询时 Router 能看到监控对象摘要，Planner 继续使用现有跨 Agent 定义，不把查询当成执行监控。

## 删除的模型字段

- changes：从 planner Schema、示例和输出要求删除。计划通过能力、结构、参数和引用校验后，不再因漏报变化调用模型修复。
- explicit_conditions：从当前规划链路消费中移除；模型偶然返回旧字段时忽略，不据其认定用户明确要求，也不因此修复。旧 trace 原文不删除。
- 程序侧 `QUERY_DIFF.actual_changes` 记录前后有效计划变化，仅用于审计。删除同交易所分支不会再标为交易所变化，目标数变化单独标记 targets。

## 本次保留的功能字段

condition_updates（set/reset）和 task_action/task_ref/state_ref 仍由 Planner 处理。本次不把任务选择也搬到 Router，避免在接入改写时改变历史回访能力。原始本轮输入与改写后的完整查询一起放在 Planner 的 user 消息中，分别用【本轮原始输入】与【本轮完整查询】标记，用于区分用户本轮 set/reset 与改写带入的继承参数；不能因为句子被改成完整查询就判为 NEW。原始问题不再拼入 system 的 conversation_context，避免在静态规则中间截断缓存前缀。普通规划修复保留这条 user 消息；专项澄清修复通过 user JSON 的 original_question 传递原话，question 仍保留独立查询目标。程序侧计划校验继续使用独立查询字符串，不使用两段合并文本。

固定参数继续继承；恢复自动必须显式 reset。澄清接续、NEW/CONTINUE/RESUME、历史状态版本恢复、pinned_result 与 evidence_ref 的有效性校验继续保留。当前字段是否继续迁移应单独评估，不直接删除。

## 校验与验证

保留指标能力、参数范围、时间契约、引用、算子输入和必要序列覆盖校验；必要的计划修复仍存在。monitor 确认和状态操作不放宽。

本地定向测试覆盖：router 改写与原话回退、正常总调用数仍为3、Planner/Answer输入分工、来源摘要不泄漏完整审计链、漏changes首轮通过、同所目标收窄审计、condition_updates、澄清接续、历史任务及事实绑定。

离线重放工具会从新 Schema 中移除 changes 和 explicit_conditions。单独重放旧 planner trace 仍使用当时保存的本轮问题和上下文，并不会自动执行新的 Router 改写；要看改写阶段输出请单独 `--stage router`。只有完整本地链路测试或部署后端到端请求，才能验证改写与规划的组合行为。

2026-11-07 原始输入消息调整：`mvn test-compile` 及 DagRuntimeTest、DagPlannerTest、AgentPlanningTest、QueryIterationTest、TaskPipelineTest 共160个本地用例通过。新增验证不同问题的空会话 system 完全一致、普通修复保留两种问题、澄清修复在 user JSON 中保留原话。未运行线上回归或收费重放，实际缓存命中率待部署后验证。
