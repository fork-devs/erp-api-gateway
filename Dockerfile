FROM kong/kong-gateway:3.6

USER root

# Declarative routing configuration
COPY kong.yml /etc/kong/kong.yml

# DB-less execution settings
ENV KONG_DATABASE=off \
    KONG_DECLARATIVE_CONFIG=/etc/kong/kong.yml \
    KONG_PLUGINS=bundled \
    KONG_PROXY_ACCESS_LOG=/dev/stdout \
    KONG_PROXY_ERROR_LOG=/dev/stderr \
    KONG_ADMIN_LISTEN=off

USER kong

EXPOSE 8000

CMD ["kong", "docker-start"]
