package com.erp.apigateway.domain.model;

import java.io.Serializable;

public record BulkheadResult(
        boolean acquired,
        int currentInFlight,
        int maxCapacity
) implements Serializable {
    public static BulkheadResult success(int inFlight, int max) {
        return new BulkheadResult(true, inFlight, max);
    }

    public static BulkheadResult rejected(int inFlight, int max) {
        return new BulkheadResult(false, inFlight, max);
    }
}
