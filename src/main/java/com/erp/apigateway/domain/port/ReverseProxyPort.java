package com.erp.apigateway.domain.port;

import com.erp.apigateway.application.dto.OutboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;

public interface ReverseProxyPort {
    ProxyResponse forward(OutboundRequest request);
}
