package com.cexpilot.business.cex.conversation;

import com.cexpilot.business.cex.evidence.EvidenceAssembler;
import java.util.List;

/** 一次规划的输入投影。当前版本是唯一结构化基准，提示词与候选绑定由同一次组装产生。 */
public record PlanningContext(String contextText, TaskVersion currentVersion,
        List<EvidenceAssembler.Entry> evidence, int textHistoryCount, List<TaskVersionCandidate> candidates,
        boolean clarificationSubmission, String resolvedQuestion) {
    public PlanningContext(String text, TaskVersion current) { this(text, current, List.of(), 0, List.of()); }
    public PlanningContext(String text, TaskVersion current, List<EvidenceAssembler.Entry> evidence) {
        this(text, current, evidence, 0, List.of());
    }
    public PlanningContext(String text, TaskVersion current, List<EvidenceAssembler.Entry> evidence, int count) {
        this(text, current, evidence, count, List.of());
    }
    public PlanningContext(String text, TaskVersion current, List<EvidenceAssembler.Entry> evidence,
            int count, List<TaskVersionCandidate> candidates) { this(text, current, evidence, count, candidates, false, null); }
    public PlanningContext {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (candidates.stream().map(TaskVersionCandidate::candidateRef).distinct().count() != candidates.size())
            throw new IllegalArgumentException("版本候选引用不能重复");
        if (currentVersion != null && candidates.stream().anyMatch(c ->
                !currentVersion.conversationId().equals(c.taskVersion().conversationId())))
            throw new IllegalArgumentException("版本候选不属于当前会话");
    }
    public QueryDefinition activeDefinition() { return currentVersion == null ? null : currentVersion.queryDefinition(); }
    public PlanningContext withResolvedQuestion(String value) {
        return new PlanningContext(contextText, currentVersion, evidence, textHistoryCount, candidates, clarificationSubmission, value);
    }
    public PlanningContext withClarificationAnswers(List<ClarificationAnswer> submitted) {
        if (submitted.isEmpty()) return this;
        if (currentVersion == null || !"NEEDS_CLARIFICATION".equals(currentVersion.status()))
            throw new IllegalArgumentException("当前任务没有待补表单，请刷新历史");
        var next = currentVersion.withClarificationAnswers(submitted);
        return new PlanningContext(contextText.replace(ClarificationAnswer.render(currentVersion.clarificationAnswers()), "")
                + ClarificationAnswer.render(next.clarificationAnswers()), next, evidence, textHistoryCount, candidates, true, resolvedQuestion);
    }
}
