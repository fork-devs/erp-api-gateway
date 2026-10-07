package com.erp.apigateway.application.usecase;

import com.erp.apigateway.application.dto.InboundRequest;
import com.erp.apigateway.domain.model.RouteTarget;
import com.erp.apigateway.domain.model.TokenClaims;

public interface ValidateGatewayRequestUseCase {
    TokenClaims validateRequest(InboundRequest request, RouteTarget target);
}
