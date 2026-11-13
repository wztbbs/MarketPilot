package com.cexpilot.platform.llm;

import com.cexpilot.platform.observability.TraceSink;
import com.cexpilot.platform.prompt.PromptStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LlmCallServiceTest {
    @Test void failedTraceWriteCannotReplaceOriginalModelFailureOrUsage() {
        var original = new IllegalStateException("upstream unavailable");
        var traceFailure = new IllegalArgumentException("trace storage unavailable");
        var service = new LlmCallService((messages, tools) -> { throw original; }, new LlmConfig(), new PromptStore(new DefaultResourceLoader()));
        TraceSink sink = event -> { throw traceFailure; };
        var usages = new ArrayList<LlmUsage>();
        assertSame(original, assertThrows(IllegalStateException.class,
                () -> service.call("agent_plan", "plan_agent", List.of(ChatMessage.user("test")), "trace", sink, usages)));
        assertArrayEquals(new Throwable[]{traceFailure}, original.getSuppressed());
        assertEquals(1, usages.size());
    }
}
