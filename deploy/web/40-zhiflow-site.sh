#!/bin/sh
# Copyright (C) 2026 gaipharma.com
# SPDX-License-Identifier: AGPL-3.0-only
#
# 容器启动时生成站点配置（nginx 官方镜像会在启动前执行 /docker-entrypoint.d/ 下的脚本）：
#   /etc/nginx/certs 里有 fullchain.pem 和 privkey.pem → 开 HTTPS，HTTP 请求跳转到 HTTPS；
#   没有证书 → 只开 HTTP。
# 环境变量：SERVER_NAME（域名，多个用空格隔开，留空接受任意域名）、HTTPS_PORT（宿主机上的 HTTPS 端口，跳转地址用）。
set -eu

CERT_DIR=/etc/nginx/certs
OUT=/etc/nginx/conf.d/default.conf
NAME="${SERVER_NAME:-}"
[ -n "$NAME" ] || NAME=_

if [ -f "$CERT_DIR/fullchain.pem" ] && [ -f "$CERT_DIR/privkey.pem" ]; then
  if [ "${HTTPS_PORT:-443}" = "443" ]; then
    TARGET='https://$host$request_uri'
  else
    TARGET="https://\$host:${HTTPS_PORT}\$request_uri"
  fi
  cat > "$OUT" <<CONF
server {
    listen 80;
    server_name $NAME;
    location = /healthz { access_log off; return 200 "ok"; }
    location / { return 301 $TARGET; }
}

server {
    listen 443 ssl;
    http2 on;
    server_name $NAME;

    ssl_certificate     $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    include /etc/nginx/zhiflow/site.conf;
}
CONF
  echo "zhiflow: 已启用 HTTPS（证书 $CERT_DIR/fullchain.pem），HTTP 跳转到 HTTPS"
else
  cat > "$OUT" <<CONF
server {
    listen 80;
    server_name $NAME;

    include /etc/nginx/zhiflow/site.conf;
}
CONF
  echo "zhiflow: $CERT_DIR 下没有 fullchain.pem / privkey.pem，只开 HTTP"
fi
