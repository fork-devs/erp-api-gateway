package com.erp.apigateway.application.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record OutboundRequest(
        String targetUrl,
        String method,
        Map<String, List<String>> headers,
        byte[] body,
        int timeoutMs
) {
    public OutboundRequest {
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        if (body == null) {
            body = new byte[0];
        }
        if (timeoutMs <= 0) {
            timeoutMs = 10000;
        }
    }
}
