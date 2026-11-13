package com.cexpilot.platform.tool.config;

import java.net.URI;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 可信部署配置；不得由 Agent YAML / 模型参数覆盖地址或认证。 */
@Configuration
@ConfigurationProperties(prefix="tool-services")
public class HttpToolServices {
    /** serviceId → 服务配置；键为小写标识（如 market_data）。 */
    private Map<String,Service> services=new HashMap<>();
    public Map<String,Service> getServices(){return services;}
    public void setServices(Map<String,Service> services){this.services=services;}
    /** 单个工具服务的地址与凭证。 */
    public static class Service {
        /** 服务基础地址，仅允许 http/https，不允许 userInfo/query/fragment/路径穿越。 */
        private String baseUrl;
        /** Bearer 认证 token；空串表示不携带。 */
        private String token="";
        public String getBaseUrl(){return baseUrl;}
        public void setBaseUrl(String value){baseUrl=value;}
        public String getToken(){return token;}
        public void setToken(String value){token=value;}
        /**
         * 校验 baseUrl 与 token 的合法性，并拼接出完整端点地址（同时作为配置的提前校验）。
         *
         * @param path 工具定义中的固定路径
         * @return baseUrl 去除尾部斜杠后拼接 path 的 URI；配置非法抛 IllegalArgumentException
         */
        public URI endpoint(String path) {
            try {
            URI base=URI.create(baseUrl==null?"":baseUrl);
            if(!Set.of("http","https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null
                    || base.getQuery()!=null || base.getFragment()!=null || base.getRawPath().contains("..") || base.getRawPath().contains("%"))
                throw new IllegalArgumentException("非法 Tool 服务地址");
            if(token==null || token.contains("\r") || token.contains("\n"))throw new IllegalArgumentException("非法 Tool 服务认证配置");
            return URI.create(base.toString().replaceAll("/+$","")+path);
            } catch(IllegalArgumentException invalid){throw new IllegalArgumentException("非法 Tool 服务地址或认证配置");}
        }
        /** 不输出地址与凭证，避免日志泄露。 */
        @Override public String toString(){return "HttpToolService[redacted]";}
    }
    /**
     * 按 id 取服务配置并立即校验其地址/认证合法性。
     *
     * @param id serviceId
     * @return 合法的服务配置；未配置或非法抛 IllegalArgumentException
     */
    public Service require(String id) {
        var service=services.get(id);
        if(service==null)throw new IllegalArgumentException("未配置 Tool 服务: "+id);
        service.endpoint("/");return service;
    }
}
