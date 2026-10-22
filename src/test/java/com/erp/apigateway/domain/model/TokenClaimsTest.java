package com.erp.apigateway.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenClaimsTest {

    private static final UUID USUARIO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TALLER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void rechazaUnUsuarioNulo() {
        assertThatThrownBy(() -> new TokenClaims(null, TALLER, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void rechazaUnTallerNulo() {
        assertThatThrownBy(() -> new TokenClaims(USUARIO, null, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void normalizaLosRolesAMayusculasYDescartaLosVacios() {
        TokenClaims claims = new TokenClaims(USUARIO, TALLER, Set.of("mecanico", "  ", "ADMIN_TALLER"), "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60));

        assertThat(claims.getRoles()).containsExactlyInAnyOrder("MECANICO", "ADMIN_TALLER");
    }

    @Test
    void unTokenSinRolesNoHeredaNingunRol() {
        TokenClaims claims = new TokenClaims(USUARIO, TALLER, null, "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60));

        assertThat(claims.getRoles()).isEmpty();
    }

    @Test
    void normalizaElCorreoYElEmisorPorDefecto() {
        TokenClaims claims = new TokenClaims(USUARIO, TALLER, Set.of("MECANICO"), "  mecanico@taller.pe  ", null, null, null);

        assertThat(claims.getEmail()).isEqualTo("mecanico@taller.pe");
        assertThat(claims.getIssuer()).isEqualTo("erp-auth-service");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiresAt()).isNotNull();
    }

    @Test
    void unTokenVencidoEstaExpirado() {
        TokenClaims claims = new TokenClaims(USUARIO, TALLER, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));

        assertThat(claims.isExpired()).isTrue();
    }

    @Test
    void unTokenVigenteNoEstaExpirado() {
        TokenClaims claims = new TokenClaims(USUARIO, TALLER, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60));

        assertThat(claims.isExpired()).isFalse();
    }

    @Test
    void dosClaimsConLosMismosDatosSonIguales() {
        Instant issued = Instant.now();
        Instant expires = issued.plusSeconds(60);

        TokenClaims uno = new TokenClaims(USUARIO, TALLER, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", issued, expires);
        TokenClaims otro = new TokenClaims(USUARIO, TALLER, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", issued, expires);

        assertThat(uno).isEqualTo(otro);
        assertThat(uno.hashCode()).isEqualTo(otro.hashCode());
    }

    @Test
    void claimsDistintosNoSonIguales() {
        TokenClaims uno = new TokenClaims(USUARIO, TALLER, Set.of("MECANICO"), "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60));
        TokenClaims otro = new TokenClaims(USUARIO, TALLER, Set.of("ADMIN_TALLER"), "a@b.pe", "erp-auth-service", Instant.now(), Instant.now().plusSeconds(60));

        assertThat(uno).isNotEqualTo(otro);
        assertThat(uno).isNotEqualTo("no es un TokenClaims");
    }
}
