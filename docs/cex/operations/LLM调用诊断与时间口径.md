# LLM 调用诊断与时间口径

## 诊断入口

新的 route、plan、answer、monitor 调用在 `LLM_CALL.output_json.diagnostics` 保留指标；成功与调用失败均保留。完整接口 `/api/trace_detail/{traceId}` 与 `/api/trace/{traceId}` 可读，trace 页面增加“LLM 调用诊断”。批量 `/api/traces` 仍去掉 input_json/output_json，需要逐条拉详情。

所有 `*_ms` 为调用起点后的相对毫秒或持续时间，使用 `System.nanoTime()` 计算，不受时区影响。历史记录没有新指标，页面显示未采集，不能当作零。

| 字段 | 定义 |
| --- | --- |
| total_ms | 本次客户端调用总时长 |
| connection_wait_ms | 申请连接到取得连接的累计耗时（含连接池分配/校验）；不是远端排队时间 |
| connect_ms | 新连接建立累计耗时；复用已有连接通常为 0 |
| lease_attempts | 连接申请次数；内部重试/重定向可能使其大于 1 |
| pool_start / pool_end | 当前模型客户端独立连接池的 leased / pending / available / max 快照；不代表整个集群 |
| response_headers_ms | 从调用开始到收到最终响应头 |
| first_chunk_ms | 首个非空 SSE data 包到达时间，可能只包含角色/思考信息。旧 ttft_ms 字段保留此语义，不等于首正文 |
| first_content_ms / last_content_ms | 首个/末个非空正文增量到达时间 |
| first_reasoning_ms / last_reasoning_ms | 首个/末个非空 reasoning_content 增量到达时间 |
| max_chunk_gap_ms | 非空 data 包之间最大间隔，包含末包到结束/失败的等待；可包含本地回调处理时间，不能单独认定网络故障 |
| last_chunk_ms / chunk_count | 最后一个数据包时间及包数，不含 DONE 标记 |
| content_chars / reasoning_chars | 正文/思考字段累计 Java 字符数（UTF-16 code units），不是 token 数；不保存思考原文 |
| reasoning_tokens | 上游 usage.completion_tokens_details.reasoning_tokens；未提供则字段省略，不能用 completion_tokens 推算 |
| usage | 上游完整 usage 对象，保留供应商返回的细分字段 |
| upstream_request_ids / response_id | 白名单响应头中的请求 ID 及响应 JSON 的 id，可交供应商排查；不保存其他任意响应头 |
| finish_reason / stream_done | 上游结束原因/是否收到 DONE 标记。缺少标记不据此改变既有客户端成功判定，可用于诊断截流 |
| status_code / end_status | HTTP 状态及本地 SUCCESS/ERROR |

带工具的非流式请求也记录连接/响应头/usage/结束信息，但没有包/首正文的流式时间，不伪造 TTFT。异常指标也通过 LlmException 传给调用方的 trace；调用入口之前的失败无 HTTP 指标。

这些指标本身不改变模型思考开关或请求参数。`enable_thinking` 与 `thinking.type` 是否等价，须按供应商协议单独验证。

## 旧 DATETIME 的时间口径

conversation、conversation_query、ask_trace、trace_event、feedback 的旧 DATETIME 是数据库墙上时间。读取改为 JDBC `getObject(..., LocalDateTime.class)`，避免 `getTimestamp().toLocalDateTime()` 经驱动和 JVM 隐式换算。会话列表、历史消息、trace/event/feedback 时间对外为 ISO 8601 字符串，带明确偏移，例如 `2026-11-03T19:35:43+08:00`。

`app.database-time-zone`（环境变量 `DB_TIME_ZONE`）指定这些列实际采用的存储时区，默认 `Asia/Shanghai`，匹配当前数据库口径。它不是浏览器时区，也不是 JDBC URL 的 serverTimezone 声明；部署到其他数据库时必须匹配该数据库写入 NOW()/CURRENT_TIMESTAMP 所用的时区。不要为修复展示而平移已有记录或修改全局 JDBC 时区。

`/api/traces` 的 begin/end 由数据库 NOW() 原始本地值计算并带偏移返回；原有 SQL 仍在数据库内比较 NOW() 和 created_at，不改筛选语义。监控表的时间使用既有显式 UTC 编解码，本次不对其套用旧表时区。

修复不会回写历史数据，也不会给历史 LLM 调用补出不存在的诊断指标。
