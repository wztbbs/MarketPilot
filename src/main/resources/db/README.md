# 新库结构与存量迁移

`schema.sql` 是当前新库的完整建表结构，`migrations/` 保存存量数据库的历史升级步骤。两者需要同时维护。

- 新库直接使用 `schema.sql`，不再顺序执行历史迁移。
- 存量库按已经应用的版本执行后续迁移，遵守各文件的停服、备份及执行次数要求。
- `CREATE TABLE IF NOT EXISTS` 可以创建缺失表，但不会更新已存在表的字段、默认值或索引。更新 schema.sql 不等于已升级存量数据库。
- 新增迁移时把最终 DDL 同步到 schema.sql；历史中间态、数据转换 UPDATE 和已被删除的字段不直接复制回新库结构。

## 2026-11-12 同步核对结果

按编号归并 001～022 的 CREATE、ALTER、DROP，并与 schema.sql 比较。已检查 22 个迁移文件、52 条 DDL，覆盖迁移涉及的 15 张最终表、140 个最终字段定义和 39 个主键／唯一键／普通索引定义。新库 schema.sql 总计 22 张表，还包含历史基础表及底座运行表。

核对包含字段类型、长度／时间精度、可空性、默认值、ON UPDATE、索引类型和列顺序，以及迁移新建表的完整字段／索引集合、存储引擎和字符集。不要求建表时的物理字段排列顺序相同。

**未发现迁移到 schema.sql 的漏同步或定义不一致，本次没有修改 DDL。** 仅修正原文件头中“Phase 1”的过时标注并补充维护说明。本次是文件结构核对，没有连接或修改线上数据库，也没有执行历史数据转换。

| 迁移 | 最终结构核对 |
| --- | --- |
| 001、002、004、005 | ask_trace 的 intent、visitor_id、build_commit 与 created_at 索引已包含 |
| 003 | conversation 的 visitor_id 及访客索引已包含 |
| 006 | trace_event 的 ttft_ms、cached_tokens 已包含 |
| 007 | 保留的轮次生命周期字段与唯一键、conversation_state 结构已包含；旧状态列按后续迁移移除 |
| 008 | conversation_artifact、event_type 加宽与 reused_facts 已包含 |
| 009 | 独立执行计数列已包含 |
| 010 | last_query_no 默认 0 已包含；next_query_no 已移除 |
| 011、012 | 中间态缓存与轮次 frame 快照已按 021／022 移除，不补回 |
| 013 | 旧 conversation_frame 模型已按 022 替换；保留的 task_action 已包含 |
| 014 | 监控规则／版本、判定、事件、通知表已包含，规则旧租约字段按 018 移除 |
| 015 | Agent 上下文、路由及 Trace 计数列已包含 |
| 016 | 邮箱订阅、待确认版本、来源会话／轮次、收件人与对应索引已包含 |
| 017 | monitor_execution 的最终账本字段与索引已包含，后续删除列没有保留 |
| 018 | monitor_rule.lease_until／lease_token、monitor_execution.attempt_deadline 已移除 |
| 019 | monitor_execution.spec_hash 已移除；固定 DAG 在现有 rule_spec_json 内，无新增列 |
| 020 | is_real_user 为 BOOLEAN NULL DEFAULT TRUE，与迁移最终定义一致 |
| 021 | active_frame_json、pending_task_json、last_attempt_json 已移除 |
| 022 | conversation_task、conversation_task_version、任务引用和唯一键已包含，旧 frame 表／列／索引已移除 |

011～013、017 等文件描述的是当时版本的结构，不能脱离后续 DROP／CHANGE 直接与最终新库逐项补齐。尤其不能把旧 frame 数据模型或已经移除的 Monitor 执行字段恢复到当前 schema.sql。
