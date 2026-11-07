package com.cexpilot.config;

import java.net.URI;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 可信部署配置；不得由 Agent YAML / 模型参数覆盖地址或认证。 */
@Configuration
@ConfigurationProperties(prefix="tool-services")
public class HttpToolServices {
    private Map<String,Service> services=new HashMap<>();
    public Map<String,Service> getServices(){return services;}
    public void setServices(Map<String,Service> services){this.services=services;}
    public static class Service {
        private String baseUrl;
        private String token="";
        public String getBaseUrl(){return baseUrl;}
        public void setBaseUrl(String value){baseUrl=value;}
        public String getToken(){return token;}
        public void setToken(String value){token=value;}
        public URI endpoint(String path) {
            try {
            URI base=URI.create(baseUrl==null?"":baseUrl);
            if(!Set.of("http","https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null
                    || base.getQuery()!=null || base.getFragment()!=null || base.getRawPath().contains("..") || base.getRawPath().contains("%"))
                throw new IllegalArgumentException("非法 Tool 服务地址");
            if(token==null || token.contains("\r") || token.contains("\n"))throw new IllegalArgumentException("非法 Tool 服务认证配置");
            return URI.create(base.toString().replaceAll("/+$","")+path);
            } catch(RuntimeException invalid){throw new IllegalArgumentException("非法 Tool 服务地址或认证配置");}
        }
        @Override public String toString(){return "HttpToolService[redacted]";}
    }
    public Service require(String id) {
        var service=services.get(id);
        if(service==null)throw new IllegalArgumentException("未配置 Tool 服务: "+id);
        service.endpoint("/");return service;
    }
}
