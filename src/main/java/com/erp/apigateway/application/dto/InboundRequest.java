package com.erp.apigateway.application.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record InboundRequest(
        String method,
        String path,
        String queryString,
        Map<String, List<String>> headers,
        byte[] body,
        String clientIp
) {
    public InboundRequest {
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        if (body == null) {
            body = new byte[0];
        }
    }

    public String getFirstHeader(String headerName) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
