package com.cexpilot.business.cex.monitor;

import com.cexpilot.platform.run.*;
import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.tool.control.ToolAccessPolicy;
import com.cexpilot.business.cex.metric.MetricCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** 工具参数中的来源轮次须与可信运行快照一致，模型或另一个 Agent 不能代填身份。 */
@Component
public class MonitorDraftAccessPolicy implements ToolAccessPolicy {
    private final DurableStore store;private final MetricCatalog catalog;
    public MonitorDraftAccessPolicy(DurableStore store,MetricCatalog catalog){this.store=store;this.catalog=catalog;}
    public void check(ToolDefinition definition,JsonNode args,ToolContext context){
        if(!MonitorDraftTool.NAME.equals(definition.name()))return;
        if(!MonitorAgent.DEFINITION.id().equals(context.agentId()))throw new SecurityException("该 Agent 不能保存监控草稿");
        String runId;
        if(context.invocationId()!=null)runId=store.require(DurableStore.Kind.CALL,context.invocationId()).runId();
        else {var scope=RunScope.current();if(scope==null)throw new SecurityException("监控草稿缺少可信运行");runId=scope.runId();}
        var run=store.require(DurableStore.Kind.RUN,runId);
        if(!Objects.equals(context.visitorId(),run.owner()) || !Objects.equals(context.conversationId(),run.data().at("/session/conversationId").asText(null))
                || !Objects.equals(args.path("source_turn_id").asText(),run.data().at("/session/turnId").asText())
                || !catalog.version().equals(args.path("catalog_version").asText()))throw new SecurityException("草稿来源或指标契约不一致");
    }
}
