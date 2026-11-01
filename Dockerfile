FROM kong:3.6-alpine

USER root

# Install plugins for JWKS and OIDC token validation
RUN apk add --no-cache git unzip curl openssl-dev build-base && \
    luarocks install kong-openid-connect && \
    luarocks install lua-resty-openidc && \
    apk del git unzip build-base

# Declarative routing configuration
COPY kong.yml /etc/kong/kong.yml

# DB-less execution settings
ENV KONG_DATABASE=off \
    KONG_DECLARATIVE_CONFIG=/etc/kong/kong.yml \
    KONG_PLUGINS=bundled,openid-connect \
    KONG_PROXY_ACCESS_LOG=/dev/stdout \
    KONG_PROXY_ERROR_LOG=/dev/stderr \
    KONG_ADMIN_LISTEN=off

USER kong

EXPOSE 8000

CMD ["kong", "docker-start"]
