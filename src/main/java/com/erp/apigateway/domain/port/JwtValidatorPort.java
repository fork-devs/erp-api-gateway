package com.erp.apigateway.domain.port;

import com.erp.apigateway.domain.model.TokenClaims;

public interface JwtValidatorPort {
    TokenClaims validate(String bearerToken);
}
