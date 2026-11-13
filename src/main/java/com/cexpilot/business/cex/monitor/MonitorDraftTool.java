package com.cexpilot.business.cex.monitor;

import com.cexpilot.platform.tool.*;
import com.cexpilot.platform.evidence.EvidenceSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** 保存待确认定义，不启用规则、不发信。只有 Monitor 的服务端规划组装能绑定此工具。 */
@Component
public class MonitorDraftTool implements AgentTool {
    public static final String NAME="monitor.rule.create_draft";
    private final org.springframework.beans.factory.ObjectProvider<MonitorConversationFlow> flow;
    public MonitorDraftTool(org.springframework.beans.factory.ObjectProvider<MonitorConversationFlow> flow){this.flow=flow;}
    public String name(){return NAME;}
    public ToolResult execute(JsonNode args,ToolContext context){
        if(context.invocationId()==null)throw new SecurityException("保存草稿必须经过持久化工具入口");
        return ToolResult.success(flow.getObject().saveDraftForRun(context.visitorId(),new EvidenceSession(context.conversationId(),args.path("source_turn_id").asText()),
                args.path("question").asText(),args.path("candidate"),args.path("timezone").asText()));
    }
}
