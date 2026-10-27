# 智流商城（ZhiFlow Mall）

单店铺电商后端：商品 → 购物车 → 下单 → 支付 → 发货 → 收货。一个可执行 jar，必需的外部组件只有 PostgreSQL 和 Valkey（或 Redis）。

- 后端：本仓库（Java 25、Spring Boot 4、Spring Security / Authorization Server、MyBatis-Plus、Flyway）
- 管理后台（`zhiflow-admin`，Vue 3）、H5 用户端（`zhiflow-uniapp`，uni-app）：独立仓库，用本仓库的 Docker Compose 一起部署

## 功能

**用户端（H5）**：首页（轮播图、热搜、分类、商品列表）、分类、搜索、商品详情（含规格参数）、购物车、确认订单、收银台、支付结果、我的订单、订单详情（取消、确认收货）、注册登录（用户名 / 手机号 + 密码；配置短信后可用验证码；微信内网页授权）、个人资料、收货地址（内置全国省市区）、账号注销、用户协议与隐私政策。

**管理后台**：首页看板、商品（多规格 / SKU、库存预警、上下架、分类、规格、品牌、参数模板、Excel 批量导入导出、素材库）、订单（查询、详情、发货、Excel 批量发货、导出、待发货、支付差错池）、会员、内容（轮播图、热搜、协议）、系统管理（管理员、角色、菜单、字典、操作日志、登录日志、系统参数、商城设置、支付配置、文件存储、微信网页授权配置）。

**支付**：微信 H5（微信外）、微信 JSAPI（微信内）、支付宝 H5。回调验签后同步入账（分布式锁 + 数据库重查保证幂等）；待支付订单定时向渠道回查；超时自动关单、自动确认收货；已取消订单收到回调、重复支付、金额不符进入差错池，后台人工处理。

**其它**：订单与支付单都由状态机推进（跳转表是唯一真源，每次跳转写流水），数据库触发器兜底拦截绕过状态机的状态修改；文件存储默认本地目录，可切到 MinIO 或其它 S3 兼容对象存储；商品搜索默认查数据库，可选 MeiliSearch。

## 不包含 / 已知限制

- 没有退款 / 售后：真实收款后如需退款，只能在微信支付 / 支付宝商户平台手工操作，系统里的订单状态不会随之变化。
- 运费恒为 0：没有运费模板，要收运费只能计入商品售价。
- 支付只联调到「拉起渠道」为止，未与真实微信 / 支付宝商户做过端到端联调；上线前请用自己的商户号走一遍下单、支付、回调。
- 取消未支付的订单时不会去支付渠道关闭已拉起的支付单；用户若之后仍完成付款，这笔款项进入差错池，需要人工到商户平台退款。
- 物流只记录并展示快递公司和运单号，不提供物流轨迹查询。
- 单店铺；不含营销（优惠券、秒杀、拼团等）、分销、多店铺；不内置短信通道（配置 `SMS_PROVIDER` 后才有验证码登录）。

## 快速开始（Docker Compose）

需要 Docker 24+ 与 Docker Compose v2.24+（`docker compose version` 查看）。后端、管理后台、H5 三端的镜像都从源码构建，
第一次构建要下载依赖，约需十几分钟。

三个仓库并排克隆到同一个目录下（目录名要与 `.env` 里的 `ADMIN_SRC`、`H5_SRC` 对应，默认就是下面这三个名字）：

```bash
mkdir zhiflow-mall && cd zhiflow-mall
git clone https://github.com/zhiliumall/zhiflow.git zhiflow
git clone https://github.com/zhiliumall/zhiflow-admin.git zhiflow-admin
git clone https://github.com/zhiliumall/zhiflow-uniapp.git zhiflow-uniapp

cd zhiflow
./scripts/init-env.sh            # 生成 .env：随机的数据库 / 缓存口令和 id 混淆盐
docker compose up -d --build     # PostgreSQL + Valkey + 后端 + 网站入口（nginx 托管后台与 H5）
docker compose logs -f app       # 等到出现 Started ZhiflowBootApplication，按 Ctrl+C 退出
```

| 地址 | 是什么 |
| --- | --- |
| `http://localhost/` | H5 用户端（手机浏览器或电脑浏览器的手机模式打开） |
| `http://localhost/admin/` | 管理后台，账号 `admin` |

首次启动会自动建库表、写入基础数据（菜单、字典、省市区等），并为后台管理员 `admin` 生成一个随机口令，**只在日志里打印这一次**：

```bash
docker compose logs app | grep -A4 首次启动
```

用这个口令登录后台时会被要求先改密码。之后在后台建分类、商品（「商品管理」），H5 首页就有内容了。

网站入口（服务 `web`）的路径分工：`/` 是 H5，`/admin/` 是管理后台，`/mallapi/`、`/files/` 与 `/admin-api/` 反代到后端，
配置见 [`deploy/web/site.conf`](deploy/web/site.conf)。后端本身只绑在本机 `127.0.0.1:8080`（`.env` 的 `APP_PORT`），
健康检查地址是 `http://127.0.0.1:8080/actuator/health`。

### 上线：域名与 HTTPS

1. `.env` 里把 `SITE_URL` 改成网站地址（如 `https://shop.example.com`，支付回调与后台首页的 H5 二维码都用它），
   `SERVER_NAME` 填域名；
2. 把证书放进 `deploy/certs/`（或 `.env` 的 `SSL_CERT_DIR` 指向的目录），文件名为 `fullchain.pem`、`privkey.pem`；
3. `docker compose up -d --build`。有证书时自动开 HTTPS、HTTP 跳转到 HTTPS；没有证书只开 HTTP。

`SITE_URL` 会编进管理后台的构建产物，改了它要带 `--build` 重新构建。

### 升级

```bash
git -C ../zhiflow-admin pull && git -C ../zhiflow-uniapp pull && git pull
docker compose up -d --build
```

数据都在数据卷里（`pgdata` 数据库、`valkeydata` 缓存、`appdata` 上传文件与支付证书），重建容器不会丢；
**不要**加 `-v`（`docker compose down -v` 会删掉数据卷）。

### 可选组件

```bash
docker compose --profile search up -d    # MeiliSearch 商品搜索（同时把 .env 里 MEILISEARCH_ENABLED 改成 true）
```

对象存储不随 compose 提供：自备 MinIO 或其它 S3 兼容存储（如云厂商的对象存储），在后台「文件存储配置」里选「MinIO / S3」并填写地址与密钥。

### 常见问题

- **端口被占用**（`Bind for 0.0.0.0:80 failed: port is already allocated`）：在 `.env` 里改 `HTTP_PORT` / `HTTPS_PORT`
  （后端的 `APP_PORT` 同理），`SITE_URL` 也带上新端口（如 `http://localhost:8000`），然后 `docker compose up -d --build`。
- **首启口令在哪看**：`docker compose logs app | grep -A4 首次启动`。日志随容器保存，容器被重建（如 `up -d --build`、`down`）后就看不到了，
  请第一时间登录改密。忘了口令或没记下来：清空口令后重启后端，会重新生成并打印一次——
  ```bash
  docker compose exec postgres psql -U postgres -d zhiflow -c "UPDATE zhiflow_upms.sys_user SET password = '' WHERE username = 'admin'"
  docker compose restart app
  docker compose logs app | grep -A4 首次启动    # 等后端起来（约半分钟）再看，以最后一段为准
  ```
- **跨域**：三端经同一个网站入口访问，页面与接口同域，不存在跨域；后端也没有开跨域（CORS）。前端想单独部署到别的域名时，
  在那边的 nginx 上把接口前缀反代到后端，保持页面与接口同域（H5 用 `/mallapi/`、`/files/`；管理后台的前缀见其 README）。
- **上传的文件在哪**：本地存储时在后端容器的 `/app/data/uploads`（数据卷 `appdata`），经 `/files/**` 访问；
  支付证书在同一个卷的 `/app/data/home`。单个文件上限 100 MB（nginx 与后端一致）。
  备份：`docker compose cp app:/app/data ./backup-data`。多台机器部署时改用自备的 MinIO / S3 兼容存储（后台「文件存储配置」）。
- **页面打不开或接口 502**：`docker compose ps` 看各服务是否 `healthy`，后端第一次启动要建表，约需一两分钟；
  `docker compose logs app` 看后端报错。

## 配置

全部配置通过环境变量注入，说明见 [`.env.example`](.env.example)。常用的几项：

| 变量 | 说明 |
| --- | --- |
| `PG_PASSWORD` / `REDIS_PASSWORD` | 数据库与缓存口令（`init-env.sh` 自动生成） |
| `HASHIDS_SALT` | 对外 id 的混淆盐，至少 32 个字符，**上线后不能再改** |
| `SITE_URL` | 网站对外地址；支付回调 `{SITE_URL}/mallapi/paynotify/{渠道}/{租户}` 必须公网可达 |
| `HTTP_PORT` / `HTTPS_PORT` / `SERVER_NAME` / `SSL_CERT_DIR` | 网站入口的端口、域名、证书目录 |
| `ADMIN_SRC` / `H5_SRC` | 管理后台、H5 仓库的源码目录（相对本仓库） |
| `APP_PORT` | 后端端口，只绑本机回环口 |
| `NOTIFY_HOST` | 支付回调域名，默认取 `SITE_URL` |
| `SMS_PROVIDER` 等 | 短信验证码渠道（`aliyun` / `tencent`），不配则只能用户名 + 密码注册登录 |

支付参数、微信公众号 appid / secret、文件存储方式在后台页面里配置，存在数据库中。

## 本地开发

需要 JDK 25、PostgreSQL 17 或 18、Valkey 8+ 或 Redis 7+。

```bash
./mvnw -DskipTests package -pl zhiflow-boot -am     # 产物：dist/zhiflow-boot-exec.jar
PG_HOST=localhost PG_PASSWORD=... REDIS_HOST=localhost \
  java -jar dist/zhiflow-boot-exec.jar
```

开发时可用 `SPRING_PROFILES_ACTIVE=dev SMS_PROVIDER=mock`：短信验证码固定为 `9999`，**生产环境不要开**。

测试：`./mvnw verify`（单元测试 + 架构门禁）。需要真实数据库的集成测试默认跳过，例如对全部 SQL 做 `EXPLAIN` 检查：

```bash
./mvnw -pl zhiflow-boot -am test -Dtest=MapperStatementsExplainIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dbaseline.pg.url=jdbc:postgresql://localhost:5432/zhiflow -Dbaseline.pg.password=...
```

## 目录结构

```
zhiflow-boot        可执行入口（把下面各模块装配成一个进程）、全部配置
zhiflow-auth        后台登录、令牌签发
zhiflow-upms        管理员、角色、菜单、字典、日志、协议、文件上传；共享表的数据库迁移
zhiflow-mall        商品、购物车、订单、支付编排；商城表的数据库迁移
zhiflow-pay         微信、支付宝下单与查单，支付配置
zhiflow-weixin      微信网页授权
zhiflow-api         模块间接口
zhiflow-common      公共组件（数据访问、安全、日志、锁、存储、搜索、短信等）
zhiflow-arch-test   架构门禁（ArchUnit）
```

数据库结构由 Flyway 管理：`zhiflow-upms/.../db/migration/{public,upms,wx}` 与 `zhiflow-mall/.../db/migration/mall`，每个 schema 一份 `V1__baseline.sql`，之后的结构变更追加 `V2`、`V3`……

## 参与贡献

目前暂不接受外部代码提交（Pull Request），欢迎提 Issue。

发现安全漏洞请不要公开提 Issue，按 [SECURITY.md](SECURITY.md) 私下报告。

## 许可证

[AGPL-3.0](LICENSE)，另提供商业授权。

Copyright (C) 2025-2026 gaipharma.com
