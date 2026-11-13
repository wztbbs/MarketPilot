package com.cexpilot.platform.agent;

import java.util.Set;

/** Agent 的显式工具绑定，与声明式/自驱动执行方式以及持久化运行无关。 */
public interface ToolBoundAgent extends ConversationAgent {
    Set<String> allowedTools();
}
