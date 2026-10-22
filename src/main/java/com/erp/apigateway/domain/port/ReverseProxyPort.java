package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.OutboundRequest;
import com.erp.apigateway.domain.model.ProxyResponse;

public interface ReverseProxyPort {
    ProxyResponse forward(OutboundRequest request);
}
