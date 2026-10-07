package com.erp.apigateway.domain.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class TokenClaims implements Serializable {
    private final UUID userId;
    private final UUID tenantId;
    private final String role;
    private final String email;
    private final String issuer;
    private final Instant issuedAt;
    private final Instant expiresAt;

    public TokenClaims(UUID userId, UUID tenantId, String role, String email, String issuer, Instant issuedAt, Instant expiresAt) {
        if (userId == null) {
            throw new IllegalArgumentException("El userId no puede ser nulo en los claims del token");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("El tenantId no puede ser nulo en los claims del token");
        }
        this.userId = userId;
        this.tenantId = tenantId;
        this.role = role != null ? role.toUpperCase() : "MECANICO";
        this.email = email != null ? email.trim() : "";
        this.issuer = issuer != null ? issuer : "erp-auth-service";
        this.issuedAt = issuedAt != null ? issuedAt : Instant.now();
        this.expiresAt = expiresAt != null ? expiresAt : Instant.now().plusSeconds(3600);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    public String getIssuer() {
        return issuer;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TokenClaims that)) return false;
        return Objects.equals(userId, that.userId) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(role, that.role) &&
               Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tenantId, role, email);
    }
}
