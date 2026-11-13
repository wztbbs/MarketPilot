# 方案审批 Demo 验证

Demo 用于验证 ReAct／Plan 的整份方案审批和异步等待恢复，模拟“创建工单 → 指派负责人”。它使用真实底座审批、工具网关和持久化运行协议，业务工具仅操作独立的 `demo_ticket` 表，不调用交易所、邮件或其他外部业务接口。

## 启用与关闭

默认关闭，无须为正常部署增加配置。关闭时不注册 Demo Agent、工具、访问策略或模拟业务表；现有 Query／Monitor 保持原配置。

在需要验证的服务实例中设置以下环境变量，然后重启应用：

```bash
CEXPILOT_DEMO_ENABLED=true
CEXPILOT_DEMO_VISITORS=你的访客ID,另一位测试者的访客ID
CEXPILOT_DEMO_DELAY_SECONDS=15
```

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `CEXPILOT_DEMO_ENABLED` | `false` | 整组 Demo 装配开关，修改后须重启 |
| `CEXPILOT_DEMO_VISITORS` | 空 | 逗号分隔的测试访客白名单；为空时无人可用 |
| `CEXPILOT_DEMO_DELAY_SECONDS` | `15` | 模拟异步任务等待秒数，范围 1～3600；测重启建议设为 120 |

访客 ID 是浏览器访问站点后生成的 `cexpilot_uid` Cookie 值，可在浏览器开发者工具的 Cookie 列表中查看。它是现有访客身份，不是邮箱或 Agent ID。多个服务实例须使用一致的开关、白名单和 Demo 配置。声明式 Agent 还受现有 `HARNESS_HTTP_AGENTS_PERCENT` 灰度控制（默认 100），验证时需确保测试访客在灰度范围内。

白名单同时用于路由候选过滤、实际运行准入、后台恢复及工具执行。非白名单访客看不到 Demo 路由候选；Demo 工具也只能绑定给 Demo Agent。Demo 没有新增 HTTP 业务接口，仍使用原问答入口及 `/api/agent-runs/...` 的查询、审批接口。

测试结束后，先让测试运行结束，再将 `CEXPILOT_DEMO_ENABLED=false` 并重启。关闭开关不删除测试记录；不要用关闭开关来暂停运行，已等待的 Demo 运行可能因 Agent 被移除而失败。

## 验证用例

启用后，用白名单浏览器通过现有问答页面发起测试。这一步会真实调用已配置的模型；下面列出的是待执行步骤，本文不代表已完成线上验收。

| 用例 | 示例问题 | 预期检查 |
| --- | --- | --- |
| Plan 同步 | `Demo plan：同步创建标题为“审批验证”的模拟工单，然后指派给张三。` | 方案卡片含创建和指派；批准前无工单，批准后创建并指派；指派引用上游真实 ID／版本 |
| ReAct 同步 | `Demo react：同步创建标题为“React 审批验证”的模拟工单，然后指派给李四。` | 准备期间不执行操作；应将可确定的创建和指派合成一份方案；批准后顺序执行 |
| 拒绝 | 对任一新方案点击拒绝 | 本方案不执行操作；已有成功操作不会回滚 |
| 异步 | `Demo plan：异步创建标题为“异步验证”的模拟工单，然后指派给张三。`；再用 `Demo react` 重复 | 批准后运行等待，工单就绪后继续指派；等待期间不重复审批、不重复创建 |
| 执行失败 | `Demo plan：同步创建标题为“失败验证”的模拟工单，指派给 FAIL。`；再用 `Demo react` 重复 | 创建成功、指派合成失败；保留已创建工单，方案显示部分完成或失败，不能把失败指派报告为成功 |
| 身份隔离 | 换非白名单浏览器，尝试发起 Demo 请求或访问另一访客运行 | 不分发到 Demo；另一访客不能读取、审批其运行 |

如果准备结果漏掉指派、使用编造的 ID，或把本可统一审批的两步拆成多份方案，应视为模型／提示词验收问题，不能只凭最终回答判为通过。依赖真实执行结果才能确定的后续操作仍需新方案及新审批。

### 重启恢复

1. 设置异步延迟为 120 秒，重启后发起上述异步用例。
2. 记录 `runId`、`planId`、`traceId`，批准后等到创建调用进入 `WAITING`，确认模拟工单已受理。
3. 停止应用，保持数据库不变，以同一构建和配置重新启动；继续使用同一访客浏览器查询运行。
4. 等待后台补偿恢复：创建调用按已保存的 `jobId` 轮询，或按 `invocationId` 查单，然后继续指派并完成结果提交。
5. 核对 `demo_ticket` 中只新增一条对应工单、`assignee` 正确、最终 `version=2`；调用记录和方案中不应新增重复的创建操作。

Demo 以 `invocationId` 为工单 ID 和幂等键，将就绪时间保存在数据库；不依赖进程内计时器。查单找不到记录时返回结果未知，不擅自重新提交。版本检查在模拟业务服务中通过条件更新实现。

可只读核对模拟业务数据：

```sql
SELECT id, owner_id, title, ready_at, assignee, version
FROM demo_ticket
ORDER BY ready_at DESC;
```

正式的 kill／重启验收应另存运行 ID、构建版本、数据库核对结果和前后日志；历史故障验证记录不覆盖本 Demo 的整条新链路。

## 代码位置与本地检查

- 条件装配、访问隔离和模拟业务：`src/main/java/com/cexpilot/business/demo/`。
- Agent／工具定义：`src/main/resources/demo/agents/` 与 `src/main/resources/demo/tools/`；均不参与默认资源目录扫描。
- 业务提示词：`src/main/resources/prompts/demo_ticket.txt`；两个 Agent 均配置 `tool_approval: prepared_plan`。
- 模拟表仅在开关开启时通过幂等 DDL 创建，不加入 CEX 正式业务迁移。首次开启需具备建表权限，也可按 `DemoTickets` 中的 DDL 预建。
- 公共扩展点 `AgentAccessPolicy` 和 `ToolDefinitionSource` 用于条件准入及定义注册，底座不引用 Demo 业务类型。

本地检查使用 H2 MySQL 模式、固定时钟及完整应用装配，不调用真实模型或外部业务服务：

```bash
mvn test-compile
mvn test -Dtest=DemoConfigurationTest,DemoApplicationContextTest
```

这些检查覆盖默认关闭、白名单绑定、启用后的完整装配、模拟创建幂等、重建模拟服务后的查单与指派；不等同于 MySQL 下的进程强杀或浏览器端到端验收。
