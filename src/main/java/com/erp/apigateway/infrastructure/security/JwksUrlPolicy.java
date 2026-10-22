package com.erp.apigateway.infrastructure.security;

import java.net.URI;
import java.util.List;

/**
 * Politica de la URL del conjunto de claves.
 *
 * <p>El conjunto de claves se descarga por HTTP: sin cifrar, cualquiera que
 * este en el camino puede sustituir las claves y, con ellas, firmar tokens
 * que el servicio aceptara. Es un bypass de la autenticacion completo.
 *
 * <p>La unica excepcion es el perfil de desarrollo local, donde el emisor se
 * sirve en la propia maquina. Fuera de ese perfil, la URL debe usar un
 * esquema cifrado, y el arranque falla de forma explicita si no.
 */
public final class JwksUrlPolicy {

    /** Perfiles en los que se permite la URL en claro. */
    private static final List<String> PERFILES_DE_DESARROLLO = List.of("dev", "local");

    private JwksUrlPolicy() {
    }

    /**
     * Comprueba la URL y lanza una excepcion si no cumple la politica.
     */
    public static void verificar(String jwksUrl, List<String> activeProfiles) {
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException(
                    "No hay URL del conjunto de claves configurada. Defina JWKS_URL con la direccion "
                    + "del endpoint /.well-known/jwks.json del servicio de autenticacion.");
        }

        String esquema;
        try {
            esquema = URI.create(jwksUrl.trim()).getScheme();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("La URL del conjunto de claves no es valida: " + jwksUrl, e);
        }

        if ("https".equalsIgnoreCase(esquema)) {
            return;
        }

        boolean desarrolloLocal = activeProfiles != null
                && activeProfiles.stream().anyMatch(PERFILES_DE_DESARROLLO::contains);

        if (desarrolloLocal) {
            return;
        }

        throw new IllegalStateException(
                "La URL del conjunto de claves debe usar un esquema cifrado (https), y la configurada usa '"
                + esquema + "': " + jwksUrl + ". Sin cifrar, cualquiera que este en el camino puede sustituir "
                + "las claves y firmar tokens que el servicio aceptara. La unica excepcion es el perfil de "
                + "desarrollo local.");
    }
}
