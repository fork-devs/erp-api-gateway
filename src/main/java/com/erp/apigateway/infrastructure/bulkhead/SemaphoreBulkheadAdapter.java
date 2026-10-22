package com.erp.apigateway.infrastructure.bulkhead;

import com.erp.apigateway.domain.model.BulkheadResult;
import com.erp.apigateway.domain.port.BulkheadPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
        // El mamparo se REAJUSTA al limite vigente del plan: un taller que
        // sube de plan debe poder abrir mas conexiones al instante, y uno que
        // baja no debe conservar permisos del plan anterior (hallazgo 6.36).
        holder.resize(maxConcurrentSlots);

        if (holder.tryAcquire()) {
            return BulkheadResult.success(holder.inFlight(), holder.maxSlots());
        } else {
            return BulkheadResult.rejected(holder.maxSlots(), holder.maxSlots());
        }
    }

    @Override
    public void release(UUID tenantId) {
        if (tenantId == null) return;
        TenantSemaphoreHolder holder = semaphores.getIfPresent(tenantId);
        if (holder != null) {
            holder.release();
        }
    }

    /**
     * Contador de peticiones en vuelo por taller.
     *
     * <p>Se cuenta con un contador atomico y no con un {@code Semaphore}
     * porque el semaforo no se puede redimensionar, y ademas su liberacion
     * baseda en un {@code availablePermits()} que es un check-then-act: con
     * liberaciones concurrentes se podia liberar mas de lo tomado y dejar el
     * mamparo con permisos de sobra (hallazgo 6.9).
     */
    private static class TenantSemaphoreHolder {
        private final AtomicInteger inFlight = new AtomicInteger();
        private volatile int maxSlots;

        TenantSemaphoreHolder(int maxSlots) {
            this.maxSlots = maxSlots;
        }

        void resize(int newMaxSlots) {
            if (newMaxSlots > 0) {
                this.maxSlots = newMaxSlots;
            }
        }

        boolean tryAcquire() {
            while (true) {
                int current = inFlight.get();
                int max = maxSlots;
                if (current >= max) {
                    return false;
                }
                if (inFlight.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        void release() {
            while (true) {
                int current = inFlight.get();
                if (current <= 0) {
                    // Nunca se libera mas de lo tomado: una liberacion de mas
                    // abriria la puerta del mamparo sin ninguna peticion.
                    return;
                }
                if (inFlight.compareAndSet(current, current - 1)) {
                    return;
                }
            }
        }

        int inFlight() {
            return inFlight.get();
        }

        int maxSlots() {
            return maxSlots;
        }
    }
}
