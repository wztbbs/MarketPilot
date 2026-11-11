-- 删除 conversation_state 的三列历史兼容缓存：active_frame_json / pending_task_json / last_attempt_json。
--
-- 背景：这三列是多任务化（013）之前的状态存储位置；013 之后权威数据在
-- conversation_frame（任务最新态）与 conversation_query.task_state_json（轮次版本快照），
-- 三列只剩双写，新流程不再读取（降级路径已从 conversation_query 级联推导）。
--
-- 执行前请确认线上已部署不含这三列写入的版本；老会话降级读不依赖列内历史数据
-- （frame 从 conversation_query.frame_json 取，澄清/失败从最近完成轮次的 turn_status 推导）。
ALTER TABLE conversation_state DROP COLUMN active_frame_json;
ALTER TABLE conversation_state DROP COLUMN pending_task_json;
ALTER TABLE conversation_state DROP COLUMN last_attempt_json;
