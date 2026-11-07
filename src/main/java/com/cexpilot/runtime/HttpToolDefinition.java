package com.cexpilot.runtime;

/** 只读 HTTP 工具声明；地址与凭证不进入模型可选参数。 */
public record HttpToolDefinition(String serviceId,String method,String path,int timeoutMs,int maxAttempts,int maxResponseBytes,long maxAgeMs) {
    public HttpToolDefinition {
        if(serviceId==null || !serviceId.matches("[a-z][a-z0-9_-]{0,39}") || !java.util.Set.of("GET","POST").contains(method)
                || path==null || !path.matches("/[A-Za-z0-9_./-]*") || path.contains("..") || path.contains("//")
                || timeoutMs<1 || timeoutMs>30000 || maxAttempts<1 || maxAttempts>2
                || maxResponseBytes<1 || maxResponseBytes>1048576 || maxAgeMs<1 || maxAgeMs>86400000)
            throw new IllegalArgumentException("非法 HTTP Tool 配置");
    }
}
