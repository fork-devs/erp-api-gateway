package com.erp.apigateway.application.service;

import com.erp.apigateway.application.usecase.ExecuteBulkheadUseCase;
import com.erp.apigateway.domain.exception.BulkheadExceededException;
import com.erp.apigateway.domain.model.BulkheadResult;
import com.erp.apigateway.domain.port.BulkheadPort;

import java.util.UUID;

public class GatewayBulkheadService implements ExecuteBulkheadUseCase {

    private final BulkheadPort bulkheadPort;

    public GatewayBulkheadService(BulkheadPort bulkheadPort) {
        this.bulkheadPort = bulkheadPort;
    }

    @Override
    public BulkheadResult acquireSlot(UUID tenantId, int maxConcurrentSlots) {
        if (tenantId == null) {
            return BulkheadResult.success(1, 100);
        }

        BulkheadResult result = bulkheadPort.tryAcquire(tenantId, maxConcurrentSlots);
        if (!result.acquired()) {
            throw new BulkheadExceededException(
                    "Capacidad de conexiones concurrentes saturada para el taller (" + result.currentInFlight() + "/" + result.maxCapacity() + ")"
            );
        }

        return result;
    }

    @Override
    public void releaseSlot(UUID tenantId) {
        if (tenantId != null) {
            bulkheadPort.release(tenantId);
        }
    }
}
