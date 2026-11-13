package com.cexpilot.business.cex.ethereum.tool;

import com.cexpilot.business.cex.ethereum.TxAnalysisService;
import com.cexpilot.platform.tool.AgentTool;
import com.cexpilot.platform.tool.ToolContext;
import com.cexpilot.platform.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * 以太坊交易分析工具：输入 txHash，返回结构化的交易事实
 * （状态、费用、Token 转账、授权、资金流），供 LLM 解释。
 */
@Component
public class GetTransactionTool implements AgentTool {

    private final TxAnalysisService analysis;

    public GetTransactionTool(TxAnalysisService analysis) {
        this.analysis = analysis;
    }

    @Override
    public String name() {
        return "get_transaction";
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        try {
            return ToolResult.success(analysis.analyze(args.path("tx_hash").asText(null)));
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("链上数据获取失败: " + e.getMessage());
        }
    }
}
