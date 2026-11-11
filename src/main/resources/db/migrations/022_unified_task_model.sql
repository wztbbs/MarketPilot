-- 查询上下文模型切换；仅适用于已经执行 013..021 的旧部署。
-- 必须停服、备份后执行。旧查询任务及其恢复数据作废；Trace、问答文本和监控业务数据保留。
-- 不在应用启动时自动执行。新库直接使用 schema.sql，无需执行本文件。
UPDATE conversation_state SET active_frame_id = NULL;
DROP TABLE conversation_frame;
ALTER TABLE conversation_state CHANGE COLUMN active_frame_id active_task_id VARCHAR(64) NULL;
ALTER TABLE conversation_query
    DROP INDEX uk_query_frame_version,
    DROP COLUMN logical_plan_json,
    DROP COLUMN frame_json,
    DROP COLUMN frame_id,
    DROP COLUMN frame_version,
    DROP COLUMN base_frame_version,
    DROP COLUMN task_state_json,
    ADD COLUMN task_id VARCHAR(64) NULL,
    ADD COLUMN task_version BIGINT NULL,
    ADD UNIQUE KEY uk_query_task_version (conversation_id, task_id, task_version);
UPDATE conversation_query SET task_action = NULL;

CREATE TABLE conversation_task (
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
CREATE TABLE conversation_task_version (
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
