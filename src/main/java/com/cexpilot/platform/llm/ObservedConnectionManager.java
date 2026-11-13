package com.cexpilot.platform.llm;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/** RestClient classic I/O runs synchronously; the ThreadLocal is cleared after every call. */
final class ObservedConnectionManager extends PoolingHttpClientConnectionManager {
    private final ThreadLocal<LlmCallDiagnostics> current = new ThreadLocal<>();
    void begin(LlmCallDiagnostics value) { current.set(value); value.poolStart(getTotalStats()); }
    void end() { current.remove(); }

    @Override public LeaseRequest lease(String id, HttpRoute route, Timeout timeout, Object state) {
        long start = System.nanoTime();
        LlmCallDiagnostics diagnostics = current.get();
        LeaseRequest delegate = super.lease(id, route, timeout, state);
        if (diagnostics == null) return delegate;
        diagnostics.leaseAttempts++;
        return new LeaseRequest() {
            @Override public ConnectionEndpoint get(Timeout limit) throws InterruptedException,
                    java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
                try { return delegate.get(limit); }
                finally { diagnostics.connectionWaitNanos += System.nanoTime() - start; }
            }
            @Override public boolean cancel() { return delegate.cancel(); }
        };
    }
    @Override public void connect(ConnectionEndpoint endpoint, TimeValue timeout, HttpContext context)
            throws java.io.IOException {
        long start = System.nanoTime();
        try { super.connect(endpoint, timeout, context); }
        finally {
            LlmCallDiagnostics diagnostics = current.get();
            if (diagnostics != null) diagnostics.connectNanos += System.nanoTime() - start;
        }
    }
}
