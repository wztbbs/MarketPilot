package com.cexpilot.platform.run;

import com.cexpilot.platform.execution.QueryBudget;
import com.cexpilot.platform.llm.ChatResponse;
import java.util.function.Supplier;

/** 专用执行器使用稳定业务位置保存模型响应；重放不重复消耗模型预算。 */
public final class DurableModelCall {
    private DurableModelCall() {}
    public static ChatResponse invoke(String key,boolean planning,Supplier<ChatResponse> call) {
        var scope=RunScope.current();if(scope==null)return call.get();
        scope.checkLease();var saved=scope.load(key);
        if(saved!=null)return scope.json().convertValue(saved.path("response"),ChatResponse.class);
        var budget=QueryBudget.current();if(budget!=null)budget.beforeModel(planning);
        scope.saveBudget(true);
        var response=call.get();if(budget!=null)budget.observe(response);
        var value=scope.json().createObjectNode();value.set("response",scope.json().valueToTree(response));
        scope.save(key,value);scope.saveBudget();return response;
    }
}
