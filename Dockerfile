# =============================================================================
# KONG GATEWAY OSS (DB-LESS) — DRIVEOS WORKSHOP
# =============================================================================
# Imagen base oficial de Kong Open Source
FROM kong:3.6-alpine

USER root

# Instalacion de dependencias y plugin comunitario para validacion JWKS / OIDC en Kong OSS
RUN apk add --no-cache git unzip curl openssl-dev build-base && \
    luarocks install kong-openid-connect && \
    luarocks install lua-resty-openidc && \
    apk del git unzip build-base

# Copia de la configuracion declarativa GitOps
COPY kong.yml /etc/kong/kong.yml

# Variables de entorno para ejecucion sin base de datos (DB-less)
ENV KONG_DATABASE=off \
    KONG_DECLARATIVE_CONFIG=/etc/kong/kong.yml \
    KONG_PLUGINS=bundled,openid-connect \
    KONG_PROXY_ACCESS_LOG=/dev/stdout \
    KONG_PROXY_ERROR_LOG=/dev/stderr \
    KONG_ADMIN_LISTEN=off

USER kong

# Solo el puerto del proxy se expone publicamente
EXPOSE 8000

CMD ["kong", "docker-start"]
