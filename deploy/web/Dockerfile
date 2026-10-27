# Copyright (C) 2026 gaipharma.com
# SPDX-License-Identifier: AGPL-3.0-only
#
# 网站入口：一个 nginx 托管 H5（根路径）与管理后台（/admin/）的构建产物，接口反代到后端。
# 两个前端镜像由 docker-compose.yml 的 admin、h5 服务从各自仓库的源码构建，这里只拷它们的产物。

FROM nginx:stable-alpine
RUN rm -rf /usr/share/nginx/html/*
COPY --from=h5 /usr/share/nginx/html/ /usr/share/nginx/html/h5/
COPY --from=admin /usr/share/nginx/html/ /usr/share/nginx/html/admin/
COPY site.conf proxy.conf /etc/nginx/zhiflow/
COPY --chmod=755 40-zhiflow-site.sh /docker-entrypoint.d/
EXPOSE 80 443
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
