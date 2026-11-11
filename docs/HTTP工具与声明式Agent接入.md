# HTTP 工具与声明式 Agent 接入：阶段 3

阶段 3 解决两个扩展问题：新增一个符合标准协议的只读 HTTP Tool，不必新增专用 Java 执行器；新增一个采用已支持执行策略的普通 Agent，可通过版本管理的 YAML、Prompt 和 Schema 接入注册表与公共入口。

内置 Market Analysis 继续使用指标逻辑计划与 Query DAG；Monitor 继续其确认、版本、排期和通知生命周期。声明式 Agent 通过 `engine` 选择底座引擎：`react`（HTTP 工具决策循环，ReactEngine）或 `plan`（DAG 计划执行，PlanEngine），只调用明确获准的工具。这是现有指标规划器之外的受控接入路径，HTTP Tool 注册不会自动变成可查询指标，也不会自动开放给 Query 或 Monitor。

没有实现洞察、套利服务、写操作、Studio、热更新、配置上传接口或无代码业务逻辑。需要新执行生命周期时仍需代码实现。

## 最小接入

完整示例在 `examples/http-agent/`，仅声明本地模拟参考记录，不是线上行情能力，默认不加载。

1. 业务服务实现下述只读 JSON 协议，并验证服务凭证和真实资源访问范围。
2. 将示例 `tools/reference.yml`、`agents/reference.yml` 和 `prompts/local_reference.txt` 分别放入 `src/main/resources/` 对应目录。
3. 部署配置绑定实际服务。地址和认证不放在 Agent/Tool 声明，也不提供给模型：

```yaml
tool-services:
  services:
    reference:
      base-url: ${REFERENCE_SERVICE_BASE_URL}
      token: ${REFERENCE_SERVICE_TOKEN}
```

4. 重新构建并启动。服务缺失、重复 id / route、未知工具或执行模式、无效 Schema / Prompt 会在启动时失败；启动校验不探测业务网络。

Agent 示例：

```yaml
id: reference
route: reference
description: 查询本地模拟参考记录
enabled: true
engine: react
prompt: local_reference
allowed_tools: [reference.lookup]
execution:
  max_steps: 4
  max_model_calls: 6
  max_tool_calls: 3
  timeout_ms: 10000
```

Tool 示例的关键字段：

```yaml
name: reference.lookup
enabled: true
description: 本地模拟参考记录
transport: http
service_id: reference
method: POST
path: /lookup
side_effect: false
timeout_ms: 2000
max_attempts: 1
max_age_ms: 60000
input_schema:
  type: object
  additionalProperties: false
  required: [symbol]
  properties:
    symbol: {type: string, enum: [BTC, ETH]}
output_schema:
  type: object
  additionalProperties: false
  required: [symbol, value]
  properties:
    symbol: {type: string}
    value: {type: number}
```

当前 Schema 为项目支持的内联子集，不是任意 JSON Schema / OpenAPI 导入器。输入支持类型、required、枚举、数值边界、pattern、默认值以及显式声明的嵌套对象/数组；HTTP 输入对象必须封闭，不能使用旧算子那种由执行器自行解释的动态 object。输出支持类型、对象字段/required、数组/固定列和可空类型，并在实际返回时校验。具体口径仍由领域服务定义。

内置 Agent 的 id / route 不允许被 YAML 覆盖；disabled Agent 不加入 Router，disabled Tool 不可调用。工具注册、加载和授权元数据共用现有 ToolRegistry，没有增加另一套指标目录。

## HTTP 协议与身份

只支持 GET / POST。GET 参数编码到固定路径的 query，POST 参数放入 JSON body。`service_id` 绑定可信部署配置，path 是固定的绝对路径，不允许 URL、路径穿越或模型插值；禁止重定向。`side_effect=false` 是必需声明，服务方仍须保证接口实现只读，声明本身不能证明远端代码没有副作用。

HTTP 请求的可信头：

- Authorization: Bearer `<服务配置中的 token>`，如配置。
- X-Cexpilot-Trace、X-Cexpilot-Agent、X-Cexpilot-Conversation。
- X-Cexpilot-Visitor：已有访客入口传入的隔离主体；不宣称它是新增的登录账户/租户认证。
- X-Cexpilot-Request-Time、X-Cexpilot-Timezone：沿用本次请求固定的时间基准与时区；业务服务应据此解析相对时间。
- User-Agent: CEXPilot-HttpTool/1.0。

工具要求 Agent、工具白名单、访客和会话上下文存在；旧 Query 的普通 ToolContext 无此授权，无法直接执行 HTTP Tool。输入 Schema 不允许声明 userId / visitorId / tenantId / accountId、headers、URL 或凭证字段，模型不能用参数覆盖上述头。业务服务必须再次执行真实主体和资源权限校验，不能只凭模型给出的业务对象授权。

成功响应：

```json
{
  "status": "OK",
  "data": {"symbol": "BTC", "value": 123},
  "evidence": {
    "source": "local-mock",
    "observed_at": "2026-11-07T06:00:00Z",
    "valid_until": "2026-11-07T06:00:30Z",
    "unit": "USDT",
    "definition_version": "mock-v1"
  }
}
```

这是协议形状示例。实际服务需要使用当前观测或核验时间；把固定示例日期原样返回会被时效检查拒绝。observed_at 不是对交易所数据时间的替代，历史数据所属日期、原始 as_of 等应放在 data 契约字段中。重新核验历史记录不能将它解释成当前价格。

远端状态：OK、EMPTY、MISSING_DATA、DENIED、DEPENDENCY_FAILED、TIMEOUT、EXECUTION_ERROR。OK / EMPTY 均须提供符合 output_schema 的 data 与有效证据元数据；EMPTY 表示契约中的业务空结果，不能把缺失、拒绝或依赖故障降成 EMPTY。错误响应不作为行情事实，错误正文不进入模型或日志。

Harness 还可能返回 INPUT_INVALID、OUTPUT_INVALID、STALE_EVIDENCE。校验包括类型/必填字段、重复 JSON key、未知状态、元数据与时间；有效期取远端 valid_until 与本地 observed_at + max_age_ms 的较早者。非法未来时间、过期数据、超大响应和返回服务凭证的正文均被拒绝。

默认单次 HTTP Tool 总超时 5 秒，默认最多一次尝试；max_attempts 上限 2，仅对只读调用的临时 HTTP 429/502/503/504 或传输/超时失败有限重试，共享同一工具期限与剩余请求预算。认证拒绝、业务失败、Schema 错误不重试。默认响应上限 256 KiB，最大 1 MiB，输入 JSON 也使用同一大小上限。HTTP 等待包含响应体完成，不能保证撤销服务端已经接收的工作。

## 模型如何使用工具

Router 直接读取注册表中的 route 和 description，Dispatcher 不增加按业务 Agent 编写的 if/else。模型只输出以下动作，不获得任意 HTTP 能力：

```json
{"action":"call","tool":"reference.lookup","arguments":{"symbol":"BTC"}}
```

后续调用可通过 `{"$ref":"e1#/symbol"}` 引用本次执行中成功且未过期结果的 data 字段。引用先由程序解析，再按目标工具输入 Schema 校验；不允许引用其它会话、未知结果、失败或过期结果。相同工具与实际参数的重复调用以 NO_PROGRESS 停止，不重复发 HTTP。

```json
{"action":"finish","evidence_refs":["e1"]}
```

也可返回现有 clarify 字段列表。执行结束后，独立回答调用只能使用有效事实，并返回 answer 与 evidence_refs；引用不存在、失败或过期证据时不能作为用户答案交付。引用检查不是语义验真，不能声称引用合法就证明答案全部正确。

默认保留最终交付时才发送文本，不向 SSE 泄漏中途 JSON 规划。静态业务 Prompt 和工具契约放在 system，逐轮问题、历史、观察和预算放在 user，保留稳定缓存前缀。

Agent YAML 的模型、工具、时间和 observed-token 预算只能收紧公共请求预算，Router 已消耗的调用不会被重置；未使用的普通 Agent 不改变原 Query 的调用策略。

## 事实、错误和 Trace

成功 HTTP 事实沿用 EvidenceStore / conversation_artifact 保存，表中 kind=HTTP_TOOL，身份标记 kind=http_tool、Agent、工具及契约版本；记录来源、时点、单位、口径版本和参数。会话外读取仍失败。这些事实目前只供本次普通 Agent 执行与审计，不进入 Query 的指标绑定候选，也不占其最近 50 条的名额，不把任意 JSON 冒充 MetricCatalog 的指标。复用现有 kind 列，无需数据库迁移。

工具状态在 ToolResult 中显式保留，不改变旧 Java Tool 的消息形状。新增工具循环的调用失败、回答失败和 SSE 交付失败仍保留实际工具/HTTP 数、模型用量和已保存事实；未知费用不算零。

Trace 包含 AGENT_DISPATCH、LLM_CALL/agent_plan、每次 http_tool_attempt、TOOL_CALL、AGENT_REJECTED、AGENT_FINISHED。连接地址、认证头和原始依赖错误正文不写入 Trace。缓存统计将 agent_plan / query_continue 归入 plan，agent_answer 归入 answer。

本期不建立跨能力自动 Handoff 或长期任务状态。普通 Agent 回答经现有 API、SSE、会话与 Trace 返回，QueryDefinition 不作为它的私有状态；需要持久化业务生命周期的 Agent 仍应像 Monitor 一样实现业务服务。后续阶段 4 的[条件交接与灰度](交接灰度与验收.md)只适用于内置分析转监控，不将普通 HTTP 结果自动变为监控条件。

## 验证范围

本地测试使用脚本模型、localhost HTTP 桩及内存/H2 数据库；覆盖 YAML-only 接入、真实 /api/ask 控制器与 AskService 分派、身份与授权、输出/新鲜度、GET/POST、响应大小、超时、重试、重定向、参数引用、证据隔离、失败用量和原 Query/Monitor 兼容。没有发起线上 ask、收费模型调用、真实交易所请求或邮件；没有数据库迁移，未部署。

2026-11-07 验证：`mvn test-compile` 通过；23 个直接相关测试类共 395 项通过，失败/错误/跳过均为 0。数据库测试确认 55 条 HTTP 事实不会挤掉 Query 的可复用事实；HTTP 测试使用本地服务，不代表线上领域 API 已验收。
