package com.erp.apigateway.infrastructure.bulkhead;

import com.erp.apigateway.domain.model.BulkheadResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SemaphoreBulkheadTest {

    @Test
    @DisplayName("Should acquire and release concurrent slots properly")
    void shouldAcquireAndReleaseSlots() {
        SemaphoreBulkheadAdapter bulkhead = new SemaphoreBulkheadAdapter();
        UUID tenantId = UUID.randomUUID();

        // Capacidad maxima: 2 slots
        BulkheadResult slot1 = bulkhead.tryAcquire(tenantId, 2);
        assertTrue(slot1.acquired());
        assertEquals(1, slot1.currentInFlight());

        BulkheadResult slot2 = bulkhead.tryAcquire(tenantId, 2);
        assertTrue(slot2.acquired());
        assertEquals(2, slot2.currentInFlight());

        // 3er intento debe ser rechazado
        BulkheadResult slot3 = bulkhead.tryAcquire(tenantId, 2);
        assertFalse(slot3.acquired());

        // Liberar 1 slot
        bulkhead.release(tenantId);

        // Ahora el siguiente intento debe tener exito
        BulkheadResult slot4 = bulkhead.tryAcquire(tenantId, 2);
        assertTrue(slot4.acquired());
    }

    @Test
    @DisplayName("Should isolate concurrency between different tenants")
    void shouldIsolateConcurrencyBetweenTenants() {
        SemaphoreBulkheadAdapter bulkhead = new SemaphoreBulkheadAdapter();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Llenar slots de Tenant A
        assertTrue(bulkhead.tryAcquire(tenantA, 1).acquired());
        assertFalse(bulkhead.tryAcquire(tenantA, 1).acquired());

        // Tenant B puede adquirir libremente
        assertTrue(bulkhead.tryAcquire(tenantB, 1).acquired());
        assertFalse(bulkhead.tryAcquire(tenantB, 1).acquired());
    }
}
