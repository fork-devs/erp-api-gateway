package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.BulkheadResult;

import java.util.UUID;

public interface BulkheadPort {
    BulkheadResult tryAcquire(UUID tenantId, int maxConcurrentSlots);
    void release(UUID tenantId);
}
