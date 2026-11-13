package com.cexpilot.platform.evidence;

import com.cexpilot.platform.run.RunScope;
import com.cexpilot.platform.agent.AgentRunContext;
import com.cexpilot.platform.tool.ToolResult;
import java.time.Instant;
import java.util.Optional;

/** 重建执行现场时沿用已保存的事实引用，不因暂停恢复再次插入同一份证据。 */
public final class ToolEvidenceCheckpoint {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolEvidenceCheckpoint.class);
    private ToolEvidenceCheckpoint() {}
    public static Optional<String> save(ToolEvidenceWriter writer,AgentRunContext run,String agentId,String tool,String position,ToolResult result) {
        if(writer==null || run.session()==null || result.provenance()==null)return Optional.empty();
        var scope=RunScope.current();String key="evidence:"+position;
        if(scope!=null) {var saved=scope.load(key);if(saved!=null)return Optional.ofNullable(saved.path("factId").asText(null));}
        Optional<String> stored;
        try {
            stored=writer.saveToolEvidence(run.session().conversationId(),run.session().turnId(),run.traceId(),agentId,tool,result.data(),result.provenance(),Instant.now());
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new PersistenceUnavailable(failure);
        }
        if(scope!=null){var saved=scope.json().createObjectNode();stored.ifPresent(id->saved.put("factId",id));scope.save(key,saved);}
        return stored;
    }

    /** 可选证据存储的降级边界；租约、检查点和程序异常继续上抛。 */
    public static SaveResult saveOptional(ToolEvidenceWriter writer, AgentRunContext run, String agentId, String tool, String position, ToolResult result) {
        try {
            return new SaveResult(save(writer, run, agentId, tool, position, result), false);
        } catch (PersistenceUnavailable failure) {
            log.warn("可选证据保存失败 traceId={} agentId={} tool={} position={}", run.traceId(), agentId, tool, position, failure);
            return new SaveResult(Optional.empty(), true);
        }
    }

    public record SaveResult(Optional<String> factId, boolean unavailable) {}

    /** 证据适配器可显式报告存储不可用；不能用它包装租约或执行控制信号。 */
    public static final class PersistenceUnavailable extends RuntimeException {
        public PersistenceUnavailable(Throwable cause) { super("证据存储不可用", cause); }
    }
}
