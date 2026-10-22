package com.erp.apigateway.domain.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ProxyResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body
) {
    public ProxyResponse {
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        if (body == null) {
            body = new byte[0];
        }
    }
}
