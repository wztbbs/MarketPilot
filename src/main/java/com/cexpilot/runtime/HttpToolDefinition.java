package com.cexpilot.runtime;

/**
 * 只读 HTTP 工具声明；地址与凭证不进入模型可选参数。
 *
 * @param serviceId        关联的 tool-services 配置 id，决定 baseUrl 与认证
 * @param method           仅 GET 或 POST
 * @param path             固定路径，不允许 .. 或 //
 * @param timeoutMs        单次请求总超时（毫秒），1~30000
 * @param maxAttempts      最大尝试次数（含首次），1~2
 * @param maxResponseBytes 响应体上限（字节），1~1048576
 * @param maxAgeMs         证据有效期上限（毫秒），用于给 valid_until 封顶，1~86400000
 */
public record HttpToolDefinition(String serviceId,String method,String path,int timeoutMs,int maxAttempts,int maxResponseBytes,long maxAgeMs) {
    public HttpToolDefinition {
        if(serviceId==null || !serviceId.matches("[a-z][a-z0-9_-]{0,39}") || !java.util.Set.of("GET","POST").contains(method)
                || path==null || !path.matches("/[A-Za-z0-9_./-]*") || path.contains("..") || path.contains("//")
                || timeoutMs<1 || timeoutMs>30000 || maxAttempts<1 || maxAttempts>2
                || maxResponseBytes<1 || maxResponseBytes>1048576 || maxAgeMs<1 || maxAgeMs>86400000)
            throw new IllegalArgumentException("非法 HTTP Tool 配置");
    }
}
