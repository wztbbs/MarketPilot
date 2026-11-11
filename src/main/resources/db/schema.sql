-- CEXPilot Phase 1 表结构（MySQL 8，幂等，可重复执行）
-- 设计约定：用于过滤/关联的字段一律是普通列；异构 payload 才放 JSON 列。

CREATE TABLE IF NOT EXISTS conversation (
    conversation_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    visitor_id      VARCHAR(64)  NULL, -- 访客标识（cexpilot_uid cookie），当前约定 1 访客 1 会话
    title           VARCHAR(255) NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conversation_visitor (visitor_id, last_active_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_query (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    query_no        INT          NOT NULL,
    question        TEXT         NOT NULL,
    answer          MEDIUMTEXT   NULL,
    trace_id        VARCHAR(64)  NULL,
    -- 轮次生命周期（多轮追问阶段一 a）：
    -- turn_id 全局唯一标识一轮问答；parent_turn_id 记录前端声明的上一轮；turn_status 取值
    -- RUNNING / SUCCEEDED / FAILED（后续可扩展 NEEDS_CLARIFICATION）；started_at/finished_at 为服务端时间。
    turn_id         VARCHAR(64)  NULL,
    parent_turn_id  VARCHAR(64)  NULL,
    turn_status     VARCHAR(32)  NULL,
    started_at      DATETIME(6)  NULL,
    finished_at     DATETIME(6)  NULL,
    -- 轮次只关联提交的任务版本，完整状态唯一保存在 conversation_task_version。
    task_id        VARCHAR(64)   NULL,
    task_version   BIGINT        NULL,
    task_action    VARCHAR(16)   NULL,
    agent_type     VARCHAR(16)   NULL,
    agent_payload_json JSON      NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_query_turn (turn_id),
    UNIQUE KEY uk_query_conv_no (conversation_id, query_no),
    KEY idx_query_conversation (conversation_id, query_no),
    UNIQUE KEY uk_query_task_version (conversation_id, task_id, task_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 会话状态行：原子序号分配 + 同会话串行执行占用（多轮追问阶段一 a）。
-- last_query_no 存"最后已分配的序号"，初始为 0，分配时原子 +1；running_turn_id/lease_until 为执行占用，
-- fencing_token 单调递增，提交时校验——租约过期被新请求接管后，旧执行者凭过期 token 无法覆盖状态。
CREATE TABLE IF NOT EXISTS conversation_state (
    conversation_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    version         BIGINT       NOT NULL DEFAULT 0,
    last_query_no   BIGINT       NOT NULL DEFAULT 0,
    running_turn_id VARCHAR(64)  NULL,
    lease_until     DATETIME(6)  NULL,
    fencing_token   BIGINT       NOT NULL DEFAULT 0,
    -- 当前任务的稳定身份，最新状态通过任务的 latest_version 读取。
    active_task_id VARCHAR(64)   NULL,
    active_agent_type VARCHAR(16) NULL,
    monitor_planning_json JSON   NULL,
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 稳定任务身份及版本入口，不保存完整状态 JSON。
CREATE TABLE IF NOT EXISTS conversation_task (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    origin_question TEXT NOT NULL,
    first_query_no BIGINT NOT NULL,
    last_query_no BIGINT NOT NULL,
    latest_version BIGINT NOT NULL,
    latest_successful_version BIGINT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_task_recent (conversation_id, last_query_no),
    KEY idx_task_first (conversation_id, first_query_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 完整版本唯一存储；只追加，base_version 是继承来源，并非 version - 1。
CREATE TABLE IF NOT EXISTS conversation_task_version (
    task_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    base_version BIGINT NOT NULL,
    source_turn_id VARCHAR(64) NOT NULL,
    has_query_definition BOOLEAN NOT NULL,
    version_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id, version),
    UNIQUE KEY uk_task_version_turn (source_turn_id),
    KEY idx_task_version_conversation (conversation_id, task_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 旧上下文数据不兼容：已有部署需停服执行 migrations/022_unified_task_model.sql 后启动新应用。
-- Trace 与非查询任务业务数据保留，不通过旧快照恢复查询任务。

-- 一次问答 = 一条 ask_trace（就是 Trace 的主表，👎 之后靠它完整回放）
CREATE TABLE IF NOT EXISTS ask_trace (
    trace_id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(64)   NULL,
    question          TEXT          NOT NULL,
    intent            VARCHAR(64)   NULL, -- 历史意图标签，仅兼容旧数据；当前流程不再写入
    visitor_id        VARCHAR(64)   NULL, -- 访客标识（cexpilot_uid cookie）
    is_real_user      BOOLEAN       NULL DEFAULT TRUE, -- true=用户流量，false=脚本标记，NULL=历史来源未知
    status            VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    answer            MEDIUMTEXT    NULL,
    model             VARCHAR(64)   NULL,
    prompt_version    VARCHAR(16)   NULL,
    build_commit      VARCHAR(64)   NULL, -- 构建时打入的 git commit 指纹（定位答案对应的代码版本）
    llm_steps         INT           NOT NULL DEFAULT 0,
    tool_calls        INT           NOT NULL DEFAULT 0,
    reused_facts      INT           NOT NULL DEFAULT 0, -- 本轮复用的历史事实数（阶段二 FACT_REUSED 计数）
    planner_llm_calls INT           NOT NULL DEFAULT 0, -- planner 阶段 LLM 调用次数（含修复重试）
    router_llm_calls INT            NOT NULL DEFAULT 0,
    agent_type       VARCHAR(16)    NULL,
    answer_llm_calls  INT           NOT NULL DEFAULT 0, -- answer 阶段 LLM 调用次数（0 或 1）
    metric_fetch_count INT          NOT NULL DEFAULT 0, -- metric 节点真实取数次数（REUSE_FACT 不算）
    calculation_count INT           NOT NULL DEFAULT 0, -- 算子节点执行次数
    http_request_count INT          NOT NULL DEFAULT 0, -- 真实 HTTP 请求数（分页/重试逐次计）
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    cost              DECIMAL(12,8) NULL,
    duration_ms       BIGINT        NULL,
    error             TEXT          NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME      NULL,
    KEY idx_trace_conversation (conversation_id, created_at),
    KEY idx_trace_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 每次 LLM 调用 / Tool 调用 = 一条 trace_event
CREATE TABLE IF NOT EXISTS trace_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id          VARCHAR(64) NOT NULL,
    seq               INT         NOT NULL,
    event_type        VARCHAR(32) NOT NULL, -- LLM_CALL / TOOL_CALL / PLAN / METRIC_RESULT / EVIDENCE_REUSE_DECISION / FACT_REUSED 等
    name              VARCHAR(64) NULL,     -- tool 名或 llm
    input_json        JSON        NULL,
    output_json       JSON        NULL,
    duration_ms       BIGINT      NULL,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    ttft_ms           BIGINT      NULL, -- 首 token 时间（prefill 耗时），仅 LLM_CALL 流式路径有值
    cached_tokens     INT         NULL, -- prompt 缓存命中量，仅 LLM_CALL 有值
    error             TEXT        NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event_trace (trace_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 会话级取数事实（多轮追问阶段二）：一轮成功取数的完整 MetricResultJson 输出，
-- 供后续轮次以 reference_mode=pinned_result + evidence_ref 绑定复用（REUSE_FACT）。
-- kind 为 'FACT'（Query）或 'HTTP_TOOL'（普通 Agent），查询候选时先按类型隔离；
-- payload 超 256KB 不缓存（payload_json=NULL，metadata_json 记原因）；
-- retained_until = 保存时 +24h，过期清理后引用走"原快照不可恢复"澄清路径，禁止重查冒充。
CREATE TABLE IF NOT EXISTS conversation_artifact (
    artifact_id     VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL DEFAULT 'FACT',
    source_turn_id  VARCHAR(64)  NULL,
    source_trace_id VARCHAR(64)  NULL,
    lookup_key      CHAR(64)     NOT NULL,
    schema_version  VARCHAR(16)  NOT NULL,
    derived         TINYINT(1)   NOT NULL DEFAULT 0,
    metadata_json   JSON         NOT NULL,
    payload_json    JSON         NULL,
    fetched_at      DATETIME(6)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retained_until  DATETIME(6)  NOT NULL,
    KEY idx_artifact_conv (conversation_id, kind, lookup_key),
    KEY idx_artifact_retained (retained_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS feedback (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id   VARCHAR(64) NOT NULL,
    rating     VARCHAR(8)  NOT NULL, -- up / down
    category   VARCHAR(32) NULL,     -- data_error / reasoning_error / missing_info / not_understood / context_error / tool_error / other
    comment    TEXT        NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_feedback_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 历史未分类 query 表，保留旧数据；当前流程不再写入
CREATE TABLE IF NOT EXISTS unmatched_query (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NULL,
    question        TEXT         NOT NULL,
    parse_json      JSON         NULL, -- 意图识别 LLM 的完整解析结果
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_unmatched_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS eval_run (
    run_id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    eval_type         VARCHAR(32)   NOT NULL,
    category          VARCHAR(32)   NULL,
    total             INT           NOT NULL DEFAULT 0,
    passed            INT           NOT NULL DEFAULT 0,
    tool_accuracy     DECIMAL(5,4)  NULL,
    grounding_pass    INT           NOT NULL DEFAULT 0,
    model             VARCHAR(64)   NULL,
    prompt_version    VARCHAR(16)   NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS eval_case_result (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id           VARCHAR(64) NOT NULL,
    case_id          VARCHAR(64) NOT NULL,
    question         TEXT        NOT NULL,
    passed           TINYINT(1)  NOT NULL DEFAULT 0,
    expected_tools   JSON        NULL,
    actual_tools     JSON        NULL,
    missing_tools    JSON        NULL,
    forbidden_hit    JSON        NULL,
    evidence_missing JSON        NULL,
    grounding_passed TINYINT(1)  NULL,
    detail           JSON        NULL,
    trace_id         VARCHAR(64) NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_eval_result_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============ Monitor Agent V1 ============
-- 主动监控第一阶段。存量库部署前执行；新库由 schema.sql 建表。
-- 所有 DATETIME 均为 UTC。长期业务记录与 ask_trace / conversation_artifact 独立。
CREATE TABLE IF NOT EXISTS monitor_rule (
    rule_id VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_version INT NULL,
    pending_version INT NOT NULL DEFAULT 1,
    source_conversation_id VARCHAR(64) NULL,
    recipient VARCHAR(255) NULL,
    next_check_at DATETIME(6) NULL,
    last_evaluated_slot DATETIME(6) NULL,
    consecutive_hits INT NOT NULL DEFAULT 0,
    trigger_state VARCHAR(24) NOT NULL DEFAULT 'ARMED',
    hit_evidence_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_monitor_request (owner_id, request_id),
    KEY idx_monitor_due (status, next_check_at),
    KEY idx_monitor_draft (owner_id, source_conversation_id, status),
    KEY idx_monitor_owner (owner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_rule_version (
    rule_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    user_query TEXT NULL,
    rule_spec_json JSON NOT NULL,
    spec_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    approved_at DATETIME(6) NULL,
    source_turn_id VARCHAR(64) NULL,
    UNIQUE KEY uk_monitor_source_turn (source_turn_id),
    PRIMARY KEY (rule_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 灰度阶段只绑定浏览器访客与通知地址，不包含验证码或邮箱所有权证明。
CREATE TABLE IF NOT EXISTS monitor_subscriber (
    visitor_id VARCHAR(64) NOT NULL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_evaluation (
    evaluation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_version INT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    data_cutoff_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NOT NULL,
    result VARCHAR(16) NOT NULL,
    evidence_json JSON NOT NULL,
    error_code VARCHAR(64) NULL,
    UNIQUE KEY uk_monitor_check (rule_id, rule_version, scheduled_at),
    KEY idx_monitor_eval_history (rule_id, scheduled_at),
    KEY idx_monitor_eval_cleanup (finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS monitor_event (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_version INT NOT NULL,
    evaluation_id VARCHAR(64) NOT NULL,
    event_key CHAR(64) NOT NULL,
    triggered_at DATETIME(6) NOT NULL,
    evidence_snapshot JSON NOT NULL,
    UNIQUE KEY uk_monitor_event (event_key),
    KEY idx_monitor_event_history (rule_id, triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 同时作为 transactional outbox；Resend 投递复用 delivery_id 幂等键，UNKNOWN 不自动重发。
CREATE TABLE IF NOT EXISTS notification_delivery (
    delivery_id VARCHAR(64) NOT NULL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    lease_token BIGINT NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(255) NULL,
    last_error TEXT NULL,
    sent_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_monitor_delivery (event_id, channel, recipient),
    KEY idx_monitor_delivery_due (status, next_retry_at),
    KEY idx_monitor_delivery_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Execution 排期账本；存量库先完成 014/015/016，再执行本迁移一次。
CREATE TABLE IF NOT EXISTS monitor_execution (
    execution_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    rule_version INT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    checkpoint_json JSON NULL,
    result VARCHAR(16) NULL,
    last_error VARCHAR(1000) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_execution_slot (rule_id, rule_version, scheduled_at),
    KEY idx_execution_dispatch (status, scheduled_at),
    KEY idx_execution_rule (rule_id, status, scheduled_at),
    KEY idx_execution_cleanup (finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
