package com.cexpilot.business.cex.conversation;

import com.cexpilot.business.cex.metric.EffectiveTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 结构化上下文组装（多轮追问阶段一 c）：加载当前任务版本及其查询定义及近期文本历史，
 * 分层渲染成 Planner 的 conversation_context；activeDefinition 本体同时供程序侧继承对账使用。
 * 阶段二追加【可引用历史事实】段（EvidenceAssembler 候选索引，只含别名与身份摘要，不含明细）。
 *
 * 无当前任务（全新会话或只有 Monitor 轮次）时按无活动状态处理：只剩文本历史与任务索引。
 * Answer 侧仍用 ConversationService.renderRecentDialogue 的纯文本口径，本类不影响 Answer 输入。
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    private static final int HISTORY_QUERY_LIMIT = 5;
    // Planner 需要看清上一轮的口径与结论才能正确继承，截断太狠会丢指代；故比 Answer 侧（300）放宽。
    // 只裁剪文本层，结构化条件与可引用事实段不参与截断（预算不足时优先裁文本历史）。
    private static final int HISTORY_ANSWER_MAX_CHARS = 500;
    // 任务索引容量（R3 一期）：可回访任务的有限保留，超出范围澄清不硬猜
    private static final int TASK_INDEX_LIMIT = 5;
    // 可引用事实候选上限（审查 4.4）：焦点事实排最前后截断，控制 prompt 预算
    private static final int EVIDENCE_CANDIDATE_LIMIT = 10;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConversationRepository repository;
    // 阶段二：历史事实存取；null 时不渲染可引用事实段（单轮旧行为）
    private final com.cexpilot.business.cex.evidence.EvidenceStore evidenceStore;

    public ConversationContextService(ConversationRepository repository) {
        this(repository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationContextService(ConversationRepository repository,
                                      com.cexpilot.business.cex.evidence.EvidenceStore evidenceStore) {
        this.repository = repository;
        this.evidenceStore = evidenceStore;
    }

    public PlanningContext buildPlanningContext(String conversationId) {
        TaskVersion current = repository.findActiveTaskVersion(conversationId);
        List<TaskVersionCandidate> tasks = loadVersionCandidates(conversationId, current);
        // 当前任务必含于 tasks（loadVersionCandidates 首先放入 current），其焦点事实随任务快照一并收集
        java.util.Set<String> prioritized = new java.util.LinkedHashSet<>();
        for (TaskVersionCandidate task : tasks) {
            prioritized.addAll(focusFactIds(task.taskVersion().executionSummary()));
        }
        List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> evidence = loadEvidence(conversationId, prioritized);
        TextHistory history = renderTextHistory(conversationId);
        String context = render(current, history.text(), evidence, tasks);
        if (current != null) context = renderCurrentTask(current, evidence) + context;
        return new PlanningContext(context, current, evidence, history.count(), tasks);
    }

    /** Router receives semantic query context; fact binding stays with the planner. */
    public static String rewriteContext(PlanningContext input) {
        String text = input.contextText() == null ? "" : input.contextText();
        int history = text.indexOf("【近期问答】");
        if (history >= 0) text = text.substring(0,history);
        int facts = text.indexOf("【可引用历史事实】");
        if (facts >= 0) {
            int tasks = text.indexOf("【近期任务索引】",facts);
            text = text.substring(0,facts) + (tasks < 0 ? "" : text.substring(tasks));
        }
        return text;
    }

    /** 索引按任务取近期任务并保留最初任务；每项提供最新、最近版本和最初版本。 */
    private List<TaskVersionCandidate> loadVersionCandidates(String conversationId, TaskVersion current) {
        // 先选择任务：每个 taskId 只保留一份最新版本，作为后续收集该任务历史版本的入口。
        var byId = new java.util.LinkedHashMap<String, TaskVersion>();
        // 当前活动任务的最新版本优先放入，保证当前任务一定在候选范围内。
        if (current != null) byId.put(current.taskId(), current);

        // 取最近讨论过的若干任务，各取最新版本；与当前任务重合时按 taskId 去重。
        for (TaskVersion state : repository.recentTaskLatestVersions(conversationId, TASK_INDEX_LIMIT)) {
            byId.putIfAbsent(state.taskId(), state);
        }

        // 再取会话中最早创建的任务的最新版本；即使它已退出近期任务范围，也保留回访入口。
        TaskVersion first = repository.firstTaskLatestVersion(conversationId);
        if (first != null) byId.putIfAbsent(first.taskId(), first);

        List<TaskVersionCandidate> result = new java.util.ArrayList<>();
        int index = 0;
        // 再为每个入选任务收集版本：按版本号去重，并按版本号从小到大生成候选。
        for (TaskVersion latest : byId.values()) {
            var versions = new java.util.TreeMap<Long, TaskVersion>();
            // 保留该任务的最新版本，用于回到这个任务继续讨论。
            versions.put(latest.version(), latest);

            // 取该任务最近两个版本，用于回访近期修改前后的状态；其中可能包含上面的最新版本。
            for (TaskVersion state : repository.recentTaskVersions(conversationId, latest.taskId(), 2)) {
                versions.putIfAbsent(state.version(), state);
            }

            // 取当前遍历任务的第一个版本，用于回到该任务最初那一轮；每个入选任务都会取。
            TaskVersion initial = repository.firstTaskVersion(conversationId, latest.taskId());
            if (initial != null) versions.putIfAbsent(initial.version(), initial);

            // 若第一个版本没有有效查询定义（例如仍在澄清），再保留该任务最早有有效查询定义的版本。
            if (initial != null && initial.queryDefinition() == null) {
                TaskVersion executable = repository.firstExecutableTaskVersion(conversationId, latest.taskId());
                if (executable != null) versions.putIfAbsent(executable.version(), executable);
            }

            // 取该任务最近一次有效计划执行成功的版本；后续失败或澄清不会让它退出候选范围。
            TaskVersion success = repository.latestSuccessfulTaskVersion(conversationId, latest.taskId());
            if (success != null) versions.putIfAbsent(success.version(), success);

            // 校验版本归属，为每个确定的任务版本分配仅在本次规划输入中有效的候选别名。
            for (TaskVersion state : versions.values()) {
                if (!conversationId.equals(state.conversationId()) || !latest.taskId().equals(state.taskId()))
                    throw new IllegalStateException("任务版本归属与候选索引不一致");
                result.add(new TaskVersionCandidate("candidate_" + (++index), state));
            }
        }
        return List.copyOf(result);
    }

    private static String renderCurrentTask(TaskVersion state,
            List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> evidence) {
        StringBuilder out = new StringBuilder("【当前任务】task_id=").append(state.taskId())
                .append("，version=").append(state.version()).append("，status=").append(state.status())
                .append("，请求时间=").append(state.requestTime()).append("，时区=").append(state.timezone())
                .append("，原始问题：「").append(state.originQuestion()).append("」\n");
        out.append(ClarificationAnswer.render(state.clarificationAnswers()));
        if (state.queryDefinition() != null && state.queryDefinition().logicalPlan() != null && !state.dialogue().isEmpty()) {
            out.append("已保存有效计划（按本轮补充继续规划）：").append(storedPlanForContext(state, evidence)).append('\n');
        }
        if (!state.dialogue().isEmpty()) {
            out.append("【未完成任务接续】下列补充均属于该任务，不用最后一句覆盖原问题。\n");
            for (TaskVersion.Dialogue step : state.dialogue()) {
                out.append("用户：").append(step.question()).append('\n');
                if (step.response() != null) out.append("系统：").append(step.response()).append('\n');
            }
            out.append("补充条件或再试一次时延续该任务；明确换话题用 NEW。\n");
        }
        return out.toString();
    }

    /** 存储计划只含稳定 factId，展示时才生成本轮 evidence_ref，不修改持久化定义。 */
    private static com.fasterxml.jackson.databind.JsonNode storedPlanForContext(TaskVersion state,
            List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> evidence) {
        var copy = state.queryDefinition().logicalPlan().deepCopy();
        var byFact = new java.util.HashMap<String, String>();
        for (var entry : evidence) byFact.put(entry.fact().factId(), entry.ref());
        for (var metric : copy.path("metrics")) {
            if (!"pinned_result".equals(metric.path("reference_mode").asText()) || !metric.isObject()) continue;
            String ref = byFact.get(metric.path("evidence_fact_id").asText());
            var rendered = (com.fasterxml.jackson.databind.node.ObjectNode) metric;
            rendered.remove("evidence_fact_id");
            rendered.put("evidence_ref", ref == null ? "不可用" : ref);
        }
        return copy;
    }

    /** 活动 definition 焦点引用的 factId 集合（R12）：事实候选排序时排最前。 */
    private static java.util.Set<String> focusFactIds(ExecutionSummary execution) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (execution == null || execution.focus() == null) {
            return ids;
        }
        for (com.fasterxml.jackson.databind.JsonNode node : execution.focus().path("evidence_nodes")) {
            String factId = node.path("fact_id").asText(null);
            if (factId != null && !factId.isBlank()) {
                ids.add(factId);
            }
        }
        return ids;
    }

    /** 可引用历史事实：未过期且有缓存 payload 的 FACT，焦点事实（R12）排最前、其余按取数时间倒序分配稳定短别名。读不到就空列表。 */
    private List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> loadEvidence(
            String conversationId, java.util.Set<String> focusFactIds) {
        if (evidenceStore == null) {
            return List.of();
        }
        try {
            List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> entries =
                    com.cexpilot.business.cex.evidence.EvidenceAssembler.assignRefs(
                            evidenceStore.listReusable(conversationId, java.time.Instant.now()), focusFactIds);
            // 候选预算（4.4）：焦点事实已在最前，超出上限截断尾部的旧候选
            return entries.size() > EVIDENCE_CANDIDATE_LIMIT
                    ? entries.subList(0, EVIDENCE_CANDIDATE_LIMIT) : entries;
        } catch (Exception e) {
            log.warn("历史事实读取失败，本轮按无可引用事实处理 conversationId={}: {}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /** 近期问答文本 + 条数。 */
    private record TextHistory(String text, int count) {
    }

    /** 近期问答文本：带每轮发生时间（缓解跨天窗口漂移），答案截断放宽到 500。 */
    private TextHistory renderTextHistory(String conversationId) {
        List<QueryRecord> queries = repository.recentQueries(conversationId, HISTORY_QUERY_LIMIT);
        if (queries.isEmpty()) {
            return new TextHistory("", 0);
        }
        StringBuilder sb = new StringBuilder();
        for (QueryRecord query : queries) {
            sb.append("用户（").append(query.createdAt().format(HISTORY_TIME)).append("）：")
                    .append(query.question()).append('\n');
            if (query.answer() != null) {
                sb.append("助手：").append(abbreviate(query.answer(), HISTORY_ANSWER_MAX_CHARS)).append('\n');
            }
        }
        return new TextHistory(sb.toString(), queries.size());
    }

    static String render(TaskVersion version, String textHistory,
                         List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> evidence,
                         List<TaskVersionCandidate> tasks) {
        QueryDefinition definition = version == null ? null : version.queryDefinition();
        StringBuilder sb = new StringBuilder();
        if (definition != null) {
            sb.append("【活动查询状态】（程序维护的结构化条件，可信，优先于下方文字历史）\n");
            sb.append("查询定义时间基准=").append(definition.requestTime())
                    .append("，时区=").append(definition.timezone() == null ? "未携带" : definition.timezone()).append('\n');
            for (EffectiveTarget target : definition.targets()) {
                sb.append(renderTarget(target)).append('\n');
            }
            if (version.executionSummary() != null && !version.executionSummary().nodeMetadata().isEmpty())
                sb.append("上次执行元数据（不是固定要求）=").append(version.executionSummary().nodeMetadata()).append('\n');
            // 当前焦点（R12）：事实候选里焦点排最前，聚合/比较指代优先绑定
            sb.append(renderFocus(version.executionSummary(), evidence, true));
        }
        // 可引用事实段先于文本历史：预算不足时优先裁文本历史，事实索引完整保留
        sb.append(com.cexpilot.business.cex.evidence.EvidenceAssembler.render(evidence));
        // 任务索引（R3 一期）：可回访的历史任务，RESUME 的选择依据
        if (tasks != null && !tasks.isEmpty()) {
            sb.append(renderTaskIndex(tasks, evidence));
        }
        if (!textHistory.isBlank()) {
            sb.append("【近期问答】（仅辅助理解表达，不作为数据来源）\n").append(textHistory);
        }
        return sb.toString();
    }

    /** 焦点使用本轮同一份候选映射，直接给出可输出的 ref；过滤/过期的焦点明确标成不可用。 */
    private static String renderFocus(ExecutionSummary execution,
                                     List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> evidence, boolean active) {
        if (execution == null || execution.focus() == null) {
            return "";
        }
        var byFactId = new java.util.HashMap<String, com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry>();
        for (var entry : evidence) {
            byFactId.put(entry.fact().factId(), entry);
        }
        StringBuilder s = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonNode node : execution.focus().path("evidence_nodes")) {
            String factId = node.path("fact_id").asText(null);
            if (factId == null || factId.isBlank()) {
                continue;
            }
            if (s.isEmpty()) {
                s.append(active ? "【当前焦点】该状态展示的结果事实：\n" : "【该历史任务焦点】该状态展示的结果事实：\n");
            }
            s.append("- node=").append(node.path("node_id").asText())
                    .append("，metric=").append(node.path("identity").path("metric").asText());
            var entry = byFactId.get(factId);
            if (entry == null) {
                s.append("，evidence_ref=不可用（原事实不在本轮候选中，不能编造引用或用最新值冒充）");
            } else {
                s.append("，evidence_ref=").append(entry.ref())
                        .append("，source_turn_id=").append(entry.fact().sourceTurnId());
                s.append(entry.fact().derived()
                        ? "（派生结果，不可绑定为原始取数事实）"
                        : "（原始事实，可按此 ref 绑定）");
            }
            s.append('\n');
        }
        if (!s.isEmpty()) {
            s.append("用户引用该状态的这组或该结果时，使用上述可绑定 ref；有多个合理目标时澄清。\n");
        }
        return s.toString();
    }

    /** 近期任务索引段（R3 一期）：RESUME 回访的选择依据，任务主题摘要用于选择，执行条件以快照为准。 */
    private static String renderTaskIndex(List<TaskVersionCandidate> tasks,
            List<com.cexpilot.business.cex.evidence.EvidenceAssembler.Entry> evidence) {
        StringBuilder sb = new StringBuilder("【近期任务索引】按任务及版本选择。RESUME 指定一个 candidate_ref；")
                .append("回到最开始的查询选择 first_query_no 最小任务的原查询版本；最初曾澄清时可选择首个有效条件版本。明确第几轮则选择对应轮次版本。回到某任务继续可选择最新版本。")
                .append("无法唯一定位时澄清。\n");
        for (TaskVersionCandidate candidate : tasks) {
            TaskVersion version = candidate.taskVersion();
            sb.append("- candidate_ref=").append(candidate.candidateRef())
                    .append("，task_id=").append(version.taskId()).append("，version=").append(version.version())
                    .append("，first_query_no=").append(version.firstQueryNo())
                    .append("，轮次 ").append(version.turnId()).append("（第 ").append(version.lastQueryNo()).append(" 轮）")
                    .append("，status=").append(version.status()).append("，请求时间=").append(version.requestTime())
                    .append("，时区=").append(version.timezone()).append("，原始问题「").append(version.originQuestion()).append("」\n");
            if (version.queryDefinition() != null) {
                for (EffectiveTarget target : version.queryDefinition().targets()) sb.append(renderTarget(target)).append('\n');
                if (version.queryDefinition().logicalPlan() != null)
                    sb.append("完整计划=").append(storedPlanForContext(version, evidence)).append('\n');
            }
            sb.append(renderFocus(version.executionSummary(), evidence, false));
        }
        return sb.toString();
    }

    /** 单节点有效条件：metric/形态/完整 instrument/时间/粒度/count/depth 及各自来源标记。 */
    private static String renderTarget(EffectiveTarget t) {
        StringBuilder s = new StringBuilder();
        s.append("- [").append(t.groupId()).append('/').append(t.exchange()).append(']')
                .append(" metric=").append(t.metric())
                .append("，形态=").append(t.queryShape())
                .append("，instrument=").append(t.instrument())
                .append("（settle 来源 ").append(t.settleOrigin()).append(')');
        if (t.timeExpression() != null) {
            s.append("，时间=").append(t.timeExpression());
        }
        if (t.intervalOrigin() != null) {
            s.append("，粒度=").append(t.interval() == null ? "自动选择" : t.interval())
                    .append('(').append(t.intervalOrigin()).append(')');
        }
        if (t.includeUnclosedOrigin() != null) {
            s.append("，include_unclosed=").append(t.includeUnclosed())
                    .append('(').append(t.includeUnclosedOrigin()).append(')');
        }
        if (t.count() != null) {
            s.append("，count=").append(t.count());
        }
        if (t.depth() != null) {
            s.append("，depth=").append(t.depth()).append('(').append(t.depthOrigin()).append(')');
        }
        s.append("，单位=").append(t.unit()).append("，provider=").append(t.provider());
        // Only fixed-vs-default matters to model planning; the complete lineage stays in storage.
        var fixed = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (String field : List.of("interval","include_unclosed","depth","settle")) {
            var source = t.conditionSources().get(field);
            if (source != null) fixed.put(field, source.requirementOrigin() == com.cexpilot.business.cex.metric.ConditionOrigin.EXPLICIT);
        }
        if (!fixed.isEmpty()) s.append("，固定要求（true=用户固定，false=默认/自动）=").append(fixed);
        return s.toString();
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
