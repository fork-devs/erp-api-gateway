FROM kong:3.6

USER root

# Install plugins for JWKS and OIDC token validation
RUN apt-get update && \
    apt-get install -y --no-install-recommends git unzip curl libssl-dev build-essential && \
    luarocks install kong-openid-connect && \
    luarocks install lua-resty-openidc && \
    apt-get purge -y git unzip build-essential && \
    apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

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
