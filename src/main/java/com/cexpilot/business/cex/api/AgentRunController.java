package com.cexpilot.business.cex.api;

import com.cexpilot.platform.run.RunCoordinator;
import com.cexpilot.platform.tool.control.ToolGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 站点身份适配：身份仅取服务端 Cookie，Agent 与参数均取持久化记录，不接受客户端覆盖。 */
@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {
    private final RunCoordinator runs; private final ToolGateway tools;
    private final com.cexpilot.platform.plan.PreparedPlanService plans;
    public AgentRunController(RunCoordinator runs,ToolGateway tools) {this(runs,tools,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunController(RunCoordinator runs,ToolGateway tools,com.cexpilot.platform.plan.PreparedPlanService plans) {this.runs=runs;this.tools=tools;this.plans=plans;}
    public record Decision(String fingerprint, boolean approve) {}
    @GetMapping("/{id}") public Object get(@PathVariable String id,@CookieValue(value="cexpilot_uid",required=false) String visitor) {
        return runs.view(id,visitor);
    }
    @PostMapping("/{id}/plans/{planId}/decision") public Object decidePlan(@PathVariable String id,@PathVariable String planId,
            @CookieValue(value="cexpilot_uid",required=false) String visitor,@RequestHeader("X-Agent-Action") String action,@RequestBody Decision decision) {
        if(!"1".equals(action))throw new IllegalArgumentException("无效操作请求");
        runs.owned(id,visitor);
        if(plans==null)throw new IllegalStateException("方案审批服务未装配");
        plans.forRun(planId,id,visitor);plans.confirm(planId,visitor,decision.fingerprint(),decision.approve());
        return runs.view(id,visitor);
    }
    @PostMapping("/{id}/calls/{callId}/decision") public Object decide(@PathVariable String id,@PathVariable String callId,
            @CookieValue(value="cexpilot_uid",required=false) String visitor,@RequestHeader("X-Agent-Action") String action,@RequestBody Decision decision) {
        if(!"1".equals(action))throw new IllegalArgumentException("无效操作请求");
        runs.owned(id,visitor); var call=tools.owned(callId,visitor);
        if(!id.equals(call.runId()))throw new NoSuchElementException("调用不存在");
        tools.confirm(callId,visitor,decision.fingerprint(),decision.approve());return runs.view(id,visitor);
    }
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing() {return ResponseEntity.status(404).body(Map.of("error","运行或调用不存在"));}
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> denied() {return ResponseEntity.status(403).body(Map.of("error","当前授权或资源版本不允许执行"));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> conflict(IllegalArgumentException error) {return ResponseEntity.status(409).body(Map.of("error",error.getMessage()));}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<?> failed(RuntimeException error,jakarta.servlet.http.HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(AgentRunController.class).error("运行操作请求失败 path={}",request.getRequestURI(),error);
        return ResponseEntity.status(500).body(Map.of("error","运行操作暂时失败，请稍后重试"));
    }
}
