package com.cexpilot.business.cex.query;

import com.cexpilot.business.cex.metric.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** Query 私有运行协议。保存已编译的物理计划，恢复时不重新选择 Provider 或展开逻辑计划。 */
@Component
public final class QueryRunCodec {
    private final ObjectMapper json;
    public QueryRunCodec(ObjectMapper source, MetricProviderRegistry providers) {
        json=source.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var module=new SimpleModule();
        module.addSerializer(CompiledPlan.class,new JsonSerializer<CompiledPlan>() {
            public void serialize(CompiledPlan plan,JsonGenerator out,SerializerProvider provider)throws IOException {
                var data=json.createObjectNode();data.set("logical",plan.logicalPlanJson());
                data.set("dag",MetricDagCodec.write(plan.dagPlan()));data.set("branches",json.valueToTree(plan.branches()));
                data.set("targets",json.valueToTree(plan.targets()));out.writeTree(data);
            }
        });
        module.addDeserializer(CompiledPlan.class,new JsonDeserializer<CompiledPlan>() {
            public CompiledPlan deserialize(JsonParser in,DeserializationContext context)throws IOException {
                JsonNode data=in.readValueAsTree();var logical=data.get("logical");
                return new CompiledPlan(new LogicalPlanParser().parse(logical),logical,MetricDagCodec.read(data.path("dag"),providers),
                        json.convertValue(data.path("branches"),new TypeReference<Map<String,Map<String,String>>>(){}),
                        json.convertValue(data.path("targets"),new TypeReference<List<EffectiveTarget>>(){}));
            }
        });json.registerModule(module);
    }
    public ObjectNode write(Object value){return json.valueToTree(value);}
    public <T>T read(JsonNode value,Class<T> type){return json.convertValue(value,type);}
}
