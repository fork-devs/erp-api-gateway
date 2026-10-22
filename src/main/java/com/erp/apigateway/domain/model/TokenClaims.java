package com.erp.apigateway.domain.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TokenClaims implements Serializable {
    private final UUID userId;
    private final UUID tenantId;
    private final Set<String> roles;
    private final String email;
    private final String issuer;
    private final Instant issuedAt;
    private final Instant expiresAt;

    public TokenClaims(UUID userId, UUID tenantId, Set<String> roles, String email, String issuer, Instant issuedAt, Instant expiresAt) {
        if (userId == null) {
            throw new IllegalArgumentException("El userId no puede ser nulo en los claims del token");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("El tenantId no puede ser nulo en los claims del token");
        }
        this.userId = userId;
        this.tenantId = tenantId;
        // Un token sin roles NO hereda un rol por defecto: conceder un
        // privilegio que el token no declara es un bypass de autorizacion.
        // La ausencia de roles es un token invalido, y se rechaza antes de
        // llegar aqui.
        this.roles = roles != null
                ? roles.stream()
                        .filter(r -> r != null && !r.isBlank())
                        .map(String::toUpperCase)
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new))
                : Set.of();
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

    /**
     * Conjunto de roles del token. Vacio significa que el token no declara
     * ninguno, lo que no debe ocurrir en un token aceptado.
     */
    public Set<String> getRoles() {
        return roles;
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
               Objects.equals(roles, that.roles) &&
               Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tenantId, roles, email);
    }
}
