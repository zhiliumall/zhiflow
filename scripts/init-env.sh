#!/usr/bin/env sh
# 第一次部署：从 .env.example 生成 .env，填上随机的数据库 / 缓存 / MinIO / MeiliSearch 口令与 id 混淆盐。
# 已有 .env 时什么都不做（避免覆盖正在用的口令）。
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo ".env 已存在，未修改。要重新生成请先删掉它（注意：数据库口令改了，已有的数据卷会连不上）。"
  exit 0
fi
rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }
sed -e "s/^PG_PASSWORD=.*/PG_PASSWORD=$(rand 24)/" \
    -e "s/^REDIS_PASSWORD=.*/REDIS_PASSWORD=$(rand 24)/" \
    -e "s/^HASHIDS_SALT=.*/HASHIDS_SALT=$(rand 40)/" \
    -e "s/^MEILI_MASTER_KEY=.*/MEILI_MASTER_KEY=$(rand 32)/" \
    .env.example > .env
chmod 600 .env
echo "已生成 .env（口令为随机值，只保存在这个文件里）。"
