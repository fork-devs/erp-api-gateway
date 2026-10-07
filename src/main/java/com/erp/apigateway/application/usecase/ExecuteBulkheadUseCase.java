package com.erp.apigateway.application.usecase;

import com.erp.apigateway.domain.model.BulkheadResult;

import java.util.UUID;

public interface ExecuteBulkheadUseCase {
    BulkheadResult acquireSlot(UUID tenantId, int maxConcurrentSlots);
    void releaseSlot(UUID tenantId);
}
