package com.cexpilot.business.cex.evidence;

import com.cexpilot.business.cex.metric.EffectiveTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FACT 事实的组装、保存与加载。保存时机在执行后、Answer 前（DagRuntime），
 * 即使 Answer 失败事实也已落库（F13：失败后重试可复用已取事实）。
 *
 * 复用安全边界：
 * - loadForConversation 必须带会话范围，跨会话引用一律查不到；
 * - payload 单条上限 {@link #MAX_PAYLOAD_BYTES}，超限行仍落库（audit）但 payload 为空、
 *   metadata 记 cache_skip_reason，不进候选列表、不可绑定；
 * - retained_until = 保存时 + {@link #RETENTION}，过期由 deleteExpired 清理。
 */
@Component
public class EvidenceStore implements com.cexpilot.platform.evidence.ToolEvidenceWriter {
    @Override public Optional<String> saveToolEvidence(String conversationId, String turnId, String traceId,
            String agentId, String tool, JsonNode data, JsonNode provenance, Instant now) {
        return saveToolFact(conversationId, turnId, traceId, agentId, tool, data, provenance, now).map(EvidenceFact::factId);
    }

    private static final Logger log = LoggerFactory.getLogger(EvidenceStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    public static final Duration RETENTION = Duration.ofHours(24);
    /** payload 结构版本；与节点 catalogVersion 一起构成绑定的版本门槛。 */
    public static final String SCHEMA_VERSION = "1";

    private final EvidenceRepository repository;

    public EvidenceStore(EvidenceRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存一个原始取数事实（metric 节点）。payload 为该节点 MetricResultJson 输出（含 identity+data）。
     * 超限/不可序列化时返回 empty（不进候选、不可绑定），但行仍落库并记录原因。
     */
    public Optional<EvidenceFact> saveFact(String conversationId, String turnId, String traceId,
                                           EffectiveTarget target, JsonNode payload, Instant now) {
        return saveFact(conversationId,turnId,traceId,target,payload,now,null);
    }
    public Optional<EvidenceFact> saveFact(String conversationId,String turnId,String traceId,
            EffectiveTarget target,JsonNode payload,Instant now,String nodeId) {
        ObjectNode identity = identity(target, payload);
        String observedAt = payload != null && payload.has("as_of") ? payload.get("as_of").asText() : null;
        return persist(newFact(conversationId, turnId, traceId, false, List.of(),
                identity, observedAt, qualityOf(payload), payload, now,nodeId));
    }

    /**
     * 保存一个派生事实（算子结果）。derived=true，仅作审计与答案来源说明，
     * 不进样本集候选（Assembler 标注、Binder 拒绝绑定）。
     */
    public Optional<EvidenceFact> saveDerivedFact(String conversationId, String turnId, String traceId,
                                                  String operator, JsonNode payload,
                                                  List<String> sourceFactIds, Instant now) {
        return saveDerivedFact(conversationId,turnId,traceId,operator,payload,sourceFactIds,now,null);
    }
    public Optional<EvidenceFact> saveDerivedFact(String conversationId,String turnId,String traceId,
            String operator,JsonNode payload,List<String> sourceFactIds,Instant now,String nodeId) {
        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("derived", true).put("operator", operator);
        if (payload != null && payload.has("unit")) identity.put("unit", payload.get("unit").asText());
        return persist(newFact(conversationId, turnId, traceId, true, sourceFactIds,
                identity, null, null, payload, now,nodeId));
    }

    public Optional<EvidenceFact> saveToolFact(String conversationId,String turnId,String traceId,String agent,String tool,
            JsonNode payload,JsonNode provenance,Instant now) {
        ObjectNode identity=MAPPER.createObjectNode().put("kind","http_tool").put("agent",agent).put("tool",tool)
                .put("contract_version",provenance.path("contract_version").asText());
        var data=MAPPER.createObjectNode();data.set("data",payload);data.set("provenance",provenance);
        return persist(newFact(conversationId,turnId,traceId,false,List.of(),identity,provenance.path("observed_at").asText(),
                (ObjectNode)provenance.deepCopy(),data,now));
    }

    /** 必须同时校验会话范围：conversationId 不匹配的 factId 视同不存在。 */
    public Optional<EvidenceFact> loadForConversation(String conversationId, String factId) {
        return repository.find(conversationId, factId);
    }

    /** 未过期且有 payload 的候选，按取数时间倒序（短别名按此顺序稳定分配）。 */
    public List<EvidenceFact> listReusable(String conversationId, Instant now) {
        // 通用 HTTP 事实保留来源与会话隔离，但不伪装成 Query 的可绑定指标。
        return repository.listReusable(conversationId, now).stream().filter(f->!"http_tool".equals(f.identity().path("kind").asText())).toList();
    }

    public int deleteExpired(Instant now) {
        return repository.deleteExpired(now);
    }

    private EvidenceFact newFact(String conversationId, String turnId, String traceId,
                                 boolean derived, List<String> sourceFactIds,
                                 ObjectNode identity, String observedAt, ObjectNode quality,
                                 JsonNode payload, Instant now) {
        return newFact(conversationId,turnId,traceId,derived,sourceFactIds,identity,observedAt,quality,payload,now,null);
    }
    private EvidenceFact newFact(String conversationId,String turnId,String traceId,boolean derived,List<String> sourceFactIds,
            ObjectNode identity,String observedAt,ObjectNode quality,JsonNode payload,Instant now,String nodeId) {
        var run=com.cexpilot.platform.run.RunScope.current();
        String id=run==null || nodeId==null?UUID.randomUUID().toString():UUID.nameUUIDFromBytes((run.runId()+":"+turnId+":"+nodeId).getBytes(StandardCharsets.UTF_8)).toString();
        return new EvidenceFact(id, conversationId, turnId, traceId,
                derived, sourceFactIds, identity, observedAt, quality,
                lookupKey(conversationId, identity),
                payload == null ? null : payload.deepCopy(), null, now, now.plus(RETENTION), SCHEMA_VERSION);
    }

    private Optional<EvidenceFact> persist(EvidenceFact fact) {
        if (fact.payload() != null) {
            int bytes;
            try {
                bytes = fact.payload().toString().getBytes(StandardCharsets.UTF_8).length;
            } catch (Exception e) {
                log.warn("事实 payload 序列化失败，不缓存 factId={}: {}", fact.factId(), e.getMessage());
                repository.insert(factWithoutPayload(fact, "payload_not_serializable"));
                return Optional.empty();
            }
            if (bytes > MAX_PAYLOAD_BYTES) {
                log.info("事实 payload 超限（{}B > {}B），不缓存 factId={}", bytes, MAX_PAYLOAD_BYTES, fact.factId());
                repository.insert(factWithoutPayload(fact, "payload_too_large:" + bytes));
                return Optional.empty();
            }
        }
        try { repository.insert(fact); }
        catch(org.springframework.dao.DuplicateKeyException duplicate) {
            var existing=repository.find(fact.conversationId(),fact.factId());
            if(existing.isEmpty() || !java.util.Objects.equals(existing.get().sourceTurnId(),fact.sourceTurnId())
                    || !samePayload(existing.get().identity(),fact.identity()) || !samePayload(existing.get().payload(),fact.payload()))throw duplicate;
            return existing;
        }
        return Optional.of(fact);
    }

    private boolean samePayload(JsonNode left,JsonNode right) {
        try { return java.util.Objects.equals(left==null?null:MAPPER.readTree(left.toString()),right==null?null:MAPPER.readTree(right.toString())); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private EvidenceFact factWithoutPayload(EvidenceFact fact, String reason) {
        return new EvidenceFact(fact.factId(), fact.conversationId(), fact.sourceTurnId(), fact.sourceTraceId(),
                fact.derived(), fact.sourceFactIds(), fact.identity(), fact.observedAt(), fact.quality(),
                fact.lookupKey(), null, reason, fact.fetchedAt(), fact.retainedUntil(), fact.schemaVersion());
    }

    /**
     * 规范化身份：固定字段顺序、只放非空字段；时间区间用 payload 的 requested_range 原文
     * （绝对区间 + 时区，渲染口径与绑定侧 TimeRange 解析结果一致）。观测时刻不进身份——
     * 快照复用的意义正是保留旧 as_of。
     */
    public static ObjectNode identity(EffectiveTarget target, JsonNode payload) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("metric", target.metric())
                .put("exchange", target.exchange())
                .put("query_shape", target.queryShape())
                .put("unit", target.unit())
                .put("provider", target.provider())
                .put("catalog_version", target.catalogVersion());
        out.set("instrument", target.instrument().deepCopy());
        if (target.interval() != null) out.put("interval", target.interval());
        if (target.includeUnclosed() != null) out.put("include_unclosed", target.includeUnclosed());
        if (target.count() != null) out.put("count", target.count());
        if (target.depth() != null) out.put("depth", target.depth());
        if (payload != null && payload.has("requested_range")) {
            out.set("requested_range", payload.get("requested_range").deepCopy());
        }
        return out;
    }

    private static ObjectNode qualityOf(JsonNode payload) {
        if (payload == null) return null;
        ObjectNode quality = MAPPER.createObjectNode();
        if (payload.has("sample_complete")) quality.put("sample_complete", payload.get("sample_complete").asBoolean());
        if (payload.has("coverage")) {
            JsonNode coverage = payload.get("coverage");
            quality.put("range_complete", coverage.path("range_complete").asBoolean());
            quality.put("contains_unclosed", coverage.path("contains_unclosed").asBoolean());
        }
        return quality.isEmpty() ? null : quality;
    }

    /** lookup_key = SHA-256(规范化身份串)：会话 + 身份 JSON（字段顺序固定）的 hex。 */
    public static String lookupKey(String conversationId, ObjectNode identity) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest((conversationId + "|" + identity).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("lookup_key 计算失败", e);
        }
    }
}
