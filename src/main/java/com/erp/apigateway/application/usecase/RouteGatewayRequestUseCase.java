package com.erp.apigateway.application.usecase;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.application.dto.ProxyResponse;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TokenClaims;

public interface RouteGatewayRequestUseCase {
    ProxyResponse route(InboundRequest inboundRequest, RouteTarget target, TokenClaims claims, String traceId);
}
