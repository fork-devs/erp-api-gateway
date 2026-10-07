package com.erp.apigateway.infrastructure.bulkhead;

import com.erp.apigateway.domain.model.BulkheadResult;
import com.erp.apigateway.domain.port.BulkheadPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;

public class SemaphoreBulkheadAdapter implements BulkheadPort {

    private final Cache<UUID, TenantSemaphoreHolder> semaphores;

    public SemaphoreBulkheadAdapter() {
        this.semaphores = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10))
                .maximumSize(10000)
                .build();
    }

    @Override
    public BulkheadResult tryAcquire(UUID tenantId, int maxConcurrentSlots) {
        if (tenantId == null || maxConcurrentSlots <= 0) {
            return BulkheadResult.success(1, 100);
        }

        TenantSemaphoreHolder holder = semaphores.get(tenantId, id -> new TenantSemaphoreHolder(maxConcurrentSlots));
        if (holder.semaphore.tryAcquire()) {
            int inFlight = holder.maxSlots - holder.semaphore.availablePermits();
            return BulkheadResult.success(inFlight, holder.maxSlots);
        } else {
            return BulkheadResult.rejected(holder.maxSlots, holder.maxSlots);
        }
    }

    @Override
    public void release(UUID tenantId) {
        if (tenantId == null) return;
        TenantSemaphoreHolder holder = semaphores.getIfPresent(tenantId);
        if (holder != null && holder.semaphore.availablePermits() < holder.maxSlots) {
            holder.semaphore.release();
        }
    }

    private static class TenantSemaphoreHolder {
        private final Semaphore semaphore;
        private final int maxSlots;

        public TenantSemaphoreHolder(int maxSlots) {
            this.maxSlots = maxSlots;
            this.semaphore = new Semaphore(maxSlots, true);
        }
    }
}
