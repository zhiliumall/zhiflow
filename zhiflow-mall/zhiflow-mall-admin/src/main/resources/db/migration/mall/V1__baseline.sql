-- 智流商城数据库基线（zhiflow_mall_1）。
-- 首次启动时由迁移器在 search_path = 本 schema 下执行一次；之后的结构变更写 V2、V3……。
-- 结构取自裁剪后保留的表与实体实际映射的列；种子不含任何真实用户、订单与口令哈希。

CREATE TABLE global_hot_word (
    id bigint NOT NULL,
    word character varying(50) NOT NULL,
    total_count bigint DEFAULT '0'::bigint NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON COLUMN global_hot_word.word IS '热词内容';

COMMENT ON COLUMN global_hot_word.total_count IS '总搜索次数';

COMMENT ON COLUMN global_hot_word.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN global_hot_word.create_time IS '创建时间';

COMMENT ON COLUMN global_hot_word.update_time IS '最后更新时间';

CREATE SEQUENCE global_hot_word_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE global_hot_word_id_seq OWNED BY global_hot_word.id;

CREATE TABLE goods_brand (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone,
    sort integer,
    enable character varying(2) NOT NULL,
    brand_name character varying(64) NOT NULL,
    pic_url character varying(500),
    del_flag character(2) DEFAULT '0'::bpchar NOT NULL
);

COMMENT ON COLUMN goods_brand.id IS 'PK';

COMMENT ON COLUMN goods_brand.create_time IS '创建时间';

COMMENT ON COLUMN goods_brand.update_time IS '更新时间';

COMMENT ON COLUMN goods_brand.sort IS '排序字段';

COMMENT ON COLUMN goods_brand.enable IS '（1：开启；0：关闭）';

COMMENT ON COLUMN goods_brand.brand_name IS '品牌名称';

COMMENT ON COLUMN goods_brand.pic_url IS '品牌图片';

CREATE TABLE goods_category (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    enable character varying(2) NOT NULL,
    parent_id character varying(32),
    name character varying(16),
    description character varying(255),
    pic_url character varying(255),
    mobile_pic_url character varying(255),
    sort smallint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character varying(2) DEFAULT '0'::bpchar,
    page character varying(500),
    support_platforms character varying(255) DEFAULT '[]'::character varying NOT NULL,
    mobile_ad_image_url character varying(255),
    pc_big_image_url character varying(255)
);

COMMENT ON COLUMN goods_category.id IS 'PK';

COMMENT ON COLUMN goods_category.enable IS '（1：开启；0：关闭）';

COMMENT ON COLUMN goods_category.parent_id IS '父分类编号';

COMMENT ON COLUMN goods_category.name IS '名称';

COMMENT ON COLUMN goods_category.description IS '描述';

COMMENT ON COLUMN goods_category.pic_url IS '图片';

COMMENT ON COLUMN goods_category.mobile_pic_url IS '移动端图片';

COMMENT ON COLUMN goods_category.sort IS '排序';

COMMENT ON COLUMN goods_category.create_time IS '创建时间';

COMMENT ON COLUMN goods_category.update_time IS '最后更新时间';

COMMENT ON COLUMN goods_category.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_category.page IS '跳转页面';

COMMENT ON COLUMN goods_category.support_platforms IS '平台';

CREATE TABLE goods_import_task (
    id character varying(32) NOT NULL,
    tenant_id character varying(32) NOT NULL,
    del_flag character varying(2) DEFAULT '0'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    shop_id character varying(32),
    template_type character varying(16) DEFAULT 'add'::character varying NOT NULL,
    file_name character varying(255),
    file_size bigint,
    category_first character varying(32),
    category_second character varying(32),
    category_third character varying(32),
    category_name character varying(200),
    dup_strategy character varying(16) DEFAULT 'skip'::character varying NOT NULL,
    skip_invalid character varying(2) DEFAULT '1'::character varying NOT NULL,
    force_off_shelf character varying(2) DEFAULT '1'::character varying NOT NULL,
    zero_stock character varying(2) DEFAULT '0'::character varying NOT NULL,
    sync_price character varying(2) DEFAULT '0'::character varying NOT NULL,
    total_count integer DEFAULT 0 NOT NULL,
    processed_count integer DEFAULT 0 NOT NULL,
    success_count integer DEFAULT 0 NOT NULL,
    fail_count integer DEFAULT 0 NOT NULL,
    status character varying(2) DEFAULT '0'::character varying NOT NULL,
    error_msg character varying(1000),
    abort_flag character varying(2) DEFAULT '0'::character varying NOT NULL,
    operator_name character varying(64),
    rows_json text,
    fail_json text,
    source_task_id character varying(32),
    start_time timestamp without time zone,
    finish_time timestamp without time zone,
    duration_ms bigint
);

COMMENT ON TABLE goods_import_task IS '商品批量导入任务';

CREATE TABLE goods_parameter (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    name character varying(50) DEFAULT ''::character varying NOT NULL,
    parameter_template_id character varying(32),
    type character varying(32),
    sort integer,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON COLUMN goods_parameter.id IS 'PK';

COMMENT ON COLUMN goods_parameter.name IS '名称';

COMMENT ON COLUMN goods_parameter.parameter_template_id IS '参数模板ID';

COMMENT ON COLUMN goods_parameter.type IS '类型';

COMMENT ON COLUMN goods_parameter.sort IS '排序';

COMMENT ON COLUMN goods_parameter.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_parameter.create_time IS '创建时间';

COMMENT ON COLUMN goods_parameter.update_time IS '最后更新时间';

CREATE TABLE goods_parameter_template (
    id character varying(32) NOT NULL,
    sort integer,
    name character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    del_flag character(2) DEFAULT '0'::bpchar NOT NULL
);

COMMENT ON COLUMN goods_parameter_template.create_time IS '创建时间';

COMMENT ON COLUMN goods_parameter_template.update_time IS '最后更新时间';

CREATE TABLE goods_parameter_value (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    parameter_id character varying(32) NOT NULL,
    name character varying(50) DEFAULT ''::character varying NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    sort integer
);

COMMENT ON COLUMN goods_parameter_value.id IS 'PK';

COMMENT ON COLUMN goods_parameter_value.parameter_id IS '参数Id';

COMMENT ON COLUMN goods_parameter_value.name IS '名称';

COMMENT ON COLUMN goods_parameter_value.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_parameter_value.create_time IS '创建时间';

COMMENT ON COLUMN goods_parameter_value.update_time IS '最后更新时间';

COMMENT ON COLUMN goods_parameter_value.sort IS '排序';

CREATE TABLE goods_sku (
    id character varying(32) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    shop_id character varying(32) NOT NULL,
    sku_code character varying(32),
    spu_id character varying(32) NOT NULL,
    pic_url character varying(500) DEFAULT ''::character varying,
    sales_price numeric(10,2),
    market_price numeric(10,2),
    cost_price numeric(10,2),
    stock integer DEFAULT 0,
    weight numeric(10,2),
    volume numeric(10,2),
    enable character varying(2) DEFAULT '1'::bpchar NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    version integer DEFAULT 0
);

COMMENT ON COLUMN goods_sku.id IS 'PK';

COMMENT ON COLUMN goods_sku.create_time IS '创建时间';

COMMENT ON COLUMN goods_sku.update_time IS '最后更新时间';

COMMENT ON COLUMN goods_sku.shop_id IS '店铺Id';

COMMENT ON COLUMN goods_sku.sku_code IS 'sku编码';

COMMENT ON COLUMN goods_sku.spu_id IS '商品Id';

COMMENT ON COLUMN goods_sku.pic_url IS '图片';

COMMENT ON COLUMN goods_sku.sales_price IS '销售价格';

COMMENT ON COLUMN goods_sku.market_price IS '市场价';

COMMENT ON COLUMN goods_sku.cost_price IS '成本价';

COMMENT ON COLUMN goods_sku.stock IS '库存';

COMMENT ON COLUMN goods_sku.weight IS '重量（kg）';

COMMENT ON COLUMN goods_sku.volume IS '体积（m³）';

COMMENT ON COLUMN goods_sku.enable IS '是否启用1、是；0否';

COMMENT ON COLUMN goods_sku.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_sku.version IS '版本号';



CREATE TABLE goods_sku_spec_value (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    spu_id character varying(32),
    sku_id character varying(32) NOT NULL,
    spec_value_id character varying(32) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone,
    sort integer,
    del_flag character(2) DEFAULT '0'::bpchar NOT NULL
);

COMMENT ON COLUMN goods_sku_spec_value.id IS 'PK';

COMMENT ON COLUMN goods_sku_spec_value.spu_id IS 'spu_id';

COMMENT ON COLUMN goods_sku_spec_value.sku_id IS 'sku_id';

COMMENT ON COLUMN goods_sku_spec_value.spec_value_id IS '规格值id';

COMMENT ON COLUMN goods_sku_spec_value.create_time IS '创建时间';

COMMENT ON COLUMN goods_sku_spec_value.update_time IS '更新时间';

COMMENT ON COLUMN goods_sku_spec_value.sort IS '排序字段';

CREATE TABLE goods_spec (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    name character varying(50) DEFAULT ''::character varying NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON COLUMN goods_spec.id IS 'PK';

COMMENT ON COLUMN goods_spec.name IS '名称';

COMMENT ON COLUMN goods_spec.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_spec.create_time IS '创建时间';

COMMENT ON COLUMN goods_spec.update_time IS '最后更新时间';

CREATE TABLE goods_spec_value (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    spec_id character varying(32) NOT NULL,
    name character varying(50) DEFAULT ''::character varying NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN goods_spec_value.id IS 'PK';

COMMENT ON COLUMN goods_spec_value.spec_id IS '规格Id';

COMMENT ON COLUMN goods_spec_value.name IS '名称';

COMMENT ON COLUMN goods_spec_value.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_spec_value.create_time IS '创建时间';

COMMENT ON COLUMN goods_spec_value.update_time IS '最后更新时间';

CREATE TABLE goods_spu (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    shop_id character varying(32) NOT NULL,
    spu_code character varying(32) NOT NULL,
    name character varying(200) DEFAULT ''::character varying NOT NULL,
    sell_point character varying(500) DEFAULT ''::character varying NOT NULL,
    description text NOT NULL,
    category_first character varying(32) NOT NULL,
    category_second character varying(32),
    category_third character varying(32),
    video_url character varying(500),
    pic_urls character varying(1024) DEFAULT ''::character varying NOT NULL,
    shelf character varying(2) DEFAULT '0'::bpchar NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    price_down numeric(10,2),
    price_up numeric(10,2),
    sale_num integer DEFAULT 0,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    spec_type character varying(2),
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    freight_type character varying(2) NOT NULL,
    freight_amount numeric(10,2),
    verify_status character varying(2) DEFAULT '0'::bpchar NOT NULL,
    brand_id character varying(32),
    label character varying(100),
    parameter_json text,
    platform character varying(50) DEFAULT '["H5","APP","MA","H5-PC","H5-WX"]'::character varying NOT NULL,
    view_num integer DEFAULT 0,
    stock_warn_line integer
);

COMMENT ON COLUMN goods_spu.id IS 'PK';

COMMENT ON COLUMN goods_spu.shop_id IS '店铺Id';

COMMENT ON COLUMN goods_spu.spu_code IS 'spu编码';

COMMENT ON COLUMN goods_spu.name IS 'spu名字';

COMMENT ON COLUMN goods_spu.sell_point IS '卖点';

COMMENT ON COLUMN goods_spu.description IS '描述';

COMMENT ON COLUMN goods_spu.category_first IS '商城商品一级分类ID';

COMMENT ON COLUMN goods_spu.category_second IS '商城商品二级分类ID';

COMMENT ON COLUMN goods_spu.category_third IS '商城商品三级分类ID';

COMMENT ON COLUMN goods_spu.video_url IS '商品视频地址';

COMMENT ON COLUMN goods_spu.pic_urls IS '商品图片';

COMMENT ON COLUMN goods_spu.shelf IS '是否上架（1是 0否）';

COMMENT ON COLUMN goods_spu.sort IS '排序字段';

COMMENT ON COLUMN goods_spu.price_down IS '最低价';

COMMENT ON COLUMN goods_spu.price_up IS '最高价';

COMMENT ON COLUMN goods_spu.sale_num IS '销量';

COMMENT ON COLUMN goods_spu.create_time IS '创建时间';

COMMENT ON COLUMN goods_spu.update_time IS '最后更新时间';

COMMENT ON COLUMN goods_spu.spec_type IS '0统一规格；1多规格';

COMMENT ON COLUMN goods_spu.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN goods_spu.freight_type IS '运费类型（0：包邮；1：固定邮费；2：运费模板）';

COMMENT ON COLUMN goods_spu.freight_amount IS '运费金额（固定邮费必填）';

COMMENT ON COLUMN goods_spu.verify_status IS '审核状态（0审核中 1审核通过 2审核不通过）';


COMMENT ON COLUMN goods_spu.brand_id IS '品牌ID';

COMMENT ON COLUMN goods_spu.label IS '排序';

COMMENT ON COLUMN goods_spu.parameter_json IS '参数json';

COMMENT ON COLUMN goods_spu.view_num IS '浏览量';

COMMENT ON COLUMN goods_spu.stock_warn_line IS '库存预警线阈值，聚合SKU库存低于此值预警；空则兜底默认20';

CREATE TABLE goods_spu_spec (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    spu_id character varying(32) NOT NULL,
    spec_id character varying(32) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone,
    sort integer,
    spec_name character varying(255),
    del_flag character(2) DEFAULT '0'::bpchar NOT NULL
);

COMMENT ON COLUMN goods_spu_spec.id IS 'PK';

COMMENT ON COLUMN goods_spu_spec.spu_id IS 'spu_id';

COMMENT ON COLUMN goods_spu_spec.spec_id IS 'spec_id';

COMMENT ON COLUMN goods_spu_spec.create_time IS '创建时间';

COMMENT ON COLUMN goods_spu_spec.update_time IS '更新时间';

COMMENT ON COLUMN goods_spu_spec.sort IS '排序字段';

COMMENT ON COLUMN goods_spu_spec.spec_name IS '规格名称';

CREATE TABLE mall_config (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    config_key character varying(100) NOT NULL,
    config_value text,
    config_desc character varying(200),
    config_type character varying(20) DEFAULT 'string'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON COLUMN mall_config.id IS 'PK';

COMMENT ON COLUMN mall_config.config_key IS '配置键';

COMMENT ON COLUMN mall_config.config_value IS '配置值';

COMMENT ON COLUMN mall_config.config_desc IS '配置描述';

COMMENT ON COLUMN mall_config.config_type IS '配置类型（string/number/boolean/json）';

COMMENT ON COLUMN mall_config.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN mall_config.create_time IS '创建时间';

COMMENT ON COLUMN mall_config.update_time IS '更新时间';

CREATE TABLE material (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    of_type character varying(2) NOT NULL,
    shop_id character varying(32),
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_id character varying(32),
    type character varying(2) NOT NULL,
    group_id character varying(32),
    name character varying(200) NOT NULL,
    url character varying(500) DEFAULT ''::character varying
);

COMMENT ON COLUMN material.id IS 'PK';

COMMENT ON COLUMN material.of_type IS '所属类型：0:租户素材；1：店铺素材；2：供应商素材';

COMMENT ON COLUMN material.shop_id IS '店铺Id或供应商Id';

COMMENT ON COLUMN material.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN material.create_time IS '创建时间';

COMMENT ON COLUMN material.update_time IS '最后更新时间';

COMMENT ON COLUMN material.create_id IS '创建者ID';

COMMENT ON COLUMN material.type IS '类型1、图片；2、视频';

COMMENT ON COLUMN material.group_id IS '分组ID';

COMMENT ON COLUMN material.name IS '素材名';

COMMENT ON COLUMN material.url IS '素材链接';

CREATE TABLE material_group (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    of_type character varying(2) NOT NULL,
    shop_id character varying(32),
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_id character varying(32),
    name character varying(200) NOT NULL,
    material_type character varying(2) NOT NULL
);

COMMENT ON COLUMN material_group.id IS 'PK';

COMMENT ON COLUMN material_group.of_type IS '所属类型：0:租户素材；1：店铺素材；2：供应商素材';

COMMENT ON COLUMN material_group.shop_id IS '店铺Id或供应商Id';

COMMENT ON COLUMN material_group.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN material_group.create_time IS '创建时间';

COMMENT ON COLUMN material_group.update_time IS '最后更新时间';

COMMENT ON COLUMN material_group.create_id IS '创建者ID';

COMMENT ON COLUMN material_group.name IS '分组名';

COMMENT ON COLUMN material_group.material_type IS '素材类型（1：图片；2：视频）';

CREATE TABLE order_info (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    shop_id character varying(32) NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    app_type character varying(10) NOT NULL,
    app_id character varying(32),
    user_id character varying(32) NOT NULL,
    order_no character varying(50) NOT NULL,
    payment_way character varying(2) NOT NULL,
    delivery_way character varying(2) NOT NULL,
    payment_type character varying(2),
    trade_type character varying(20),
    is_pay character varying(2) NOT NULL,
    name character varying(255),
    status character varying(2),
    freight_price numeric(10,2),
    sales_price numeric(10,2) NOT NULL,
    payment_points_price numeric(10,2),
    payment_coupon_price numeric(10,2),
    payment_price numeric(10,2) NOT NULL,
    payment_points integer DEFAULT 0,
    payment_time timestamp without time zone,
    receiver_time timestamp without time zone,
    closing_time timestamp without time zone,
    user_message character varying(100),
    transaction_id character varying(32),
    order_type character varying(2) NOT NULL,
    remark character varying(255),
    rec_name character varying(100),
    rec_tel character varying(12),
    rec_address character varying(200),
    area_id character varying(20),
    logistic_status character varying(4),
    cancel_time timestamp without time zone,
    merchant_remark character varying(500),
    delivery_time timestamp with time zone,
    state_since timestamp with time zone
);

COMMENT ON COLUMN order_info.id IS 'PK';

COMMENT ON COLUMN order_info.shop_id IS '店铺Id';

COMMENT ON COLUMN order_info.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN order_info.create_time IS '创建时间';

COMMENT ON COLUMN order_info.update_time IS '最后更新时间';

COMMENT ON COLUMN order_info.app_type IS '来源应用（MA：小程序；H5：普通H5；H5-WX：微信H5；APP：app；H5-PC：PC端H5；UI：后台UI）';

COMMENT ON COLUMN order_info.app_id IS '应用ID';

COMMENT ON COLUMN order_info.user_id IS '用户id（线下收银时为后台用户ID。否则为商城用户ID）';

COMMENT ON COLUMN order_info.order_no IS '订单单号';

COMMENT ON COLUMN order_info.payment_way IS '支付方式1、货到付款；2、在线支付';

COMMENT ON COLUMN order_info.delivery_way IS '配送方式：1 普通快递';

COMMENT ON COLUMN order_info.payment_type IS '支付类型1、微信支付；2、支付宝支付；3、余额支付';

COMMENT ON COLUMN order_info.trade_type IS '交易类型（JSAPI--公众号支付、移动H5、小程序；NATIVE--原生扫码支付；APP--app支付）';

COMMENT ON COLUMN order_info.is_pay IS '是否支付0、未支付 1、已支付';

COMMENT ON COLUMN order_info.name IS '订单名';

COMMENT ON COLUMN order_info.status IS '订单状态1、待发货 2、待收货 3、确认收货/已完成 5、已关闭 10、拼团中';

COMMENT ON COLUMN order_info.freight_price IS '运费金额';

COMMENT ON COLUMN order_info.sales_price IS '销售金额';

COMMENT ON COLUMN order_info.payment_points_price IS '积分抵扣金额';

COMMENT ON COLUMN order_info.payment_coupon_price IS '电子券抵扣金额';

COMMENT ON COLUMN order_info.payment_price IS '支付金额（销售金额+运费金额-积分抵扣金额-电子券抵扣金额-用户等级折扣金额）';

COMMENT ON COLUMN order_info.payment_points IS '支付积分';

COMMENT ON COLUMN order_info.payment_time IS '付款时间';

COMMENT ON COLUMN order_info.receiver_time IS '收货时间';

COMMENT ON COLUMN order_info.closing_time IS '成交时间';

COMMENT ON COLUMN order_info.user_message IS '买家留言';

COMMENT ON COLUMN order_info.transaction_id IS '支付交易ID';

COMMENT ON COLUMN order_info.order_type IS '0、普通订单；1、砍价订单；2、拼团订单；3、秒杀订单 4、限时订单 5、新人专享 -1 混合类型 6、分销';

COMMENT ON COLUMN order_info.remark IS '备注';

COMMENT ON COLUMN order_info.rec_name IS '收件人姓名';

COMMENT ON COLUMN order_info.rec_tel IS '收件人电话';

COMMENT ON COLUMN order_info.rec_address IS '收件人地址';

COMMENT ON COLUMN order_info.area_id IS '地区ID';

COMMENT ON COLUMN order_info.logistic_status IS '0.暂无轨迹信息 1.已揽收 2.在途中 3.已签收 4.问题件 5.转寄';

COMMENT ON COLUMN order_info.cancel_time IS '取消时间（status=5 时写入）；历史数据为 NULL，读侧 COALESCE(cancel_time, update_time) 兜底';

COMMENT ON COLUMN order_info.merchant_remark IS '商家备注（管理端订单详情读写，与通用 remark/买家留言分离）';

CREATE TABLE order_item (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    shop_id character varying(32) NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    order_id character varying(32) NOT NULL,
    spu_id character varying(32),
    spu_name character varying(200),
    spec_info character varying(500),
    sku_id character varying(32) NOT NULL,
    pic_url character varying(500) NOT NULL,
    quantity integer NOT NULL,
    sales_price numeric(10,2) NOT NULL,
    freight_price numeric(10,2),
    payment_price numeric(10,2),
    payment_points_price numeric(10,2),
    payment_coupon_price numeric(10,2),
    payment_points integer DEFAULT 0,
    remark character varying(250),
    logistics_id character varying(32),
    delivery_type character varying(2),
    delivery_time timestamp without time zone,
    paid_amount numeric(10,2),
    item_status character varying(16),
    shipped_time timestamp with time zone
);

COMMENT ON COLUMN order_item.id IS 'PK';

COMMENT ON COLUMN order_item.shop_id IS '店铺Id';

COMMENT ON COLUMN order_item.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN order_item.create_time IS '创建时间';

COMMENT ON COLUMN order_item.update_time IS '最后更新时间';

COMMENT ON COLUMN order_item.order_id IS '订单id';

COMMENT ON COLUMN order_item.spu_id IS '商品Id';

COMMENT ON COLUMN order_item.spu_name IS '商品名';

COMMENT ON COLUMN order_item.spec_info IS '规格信息';

COMMENT ON COLUMN order_item.sku_id IS 'skuId';

COMMENT ON COLUMN order_item.pic_url IS '图片';

COMMENT ON COLUMN order_item.quantity IS '商品数量';

COMMENT ON COLUMN order_item.sales_price IS '购买单价';

COMMENT ON COLUMN order_item.freight_price IS '运费金额';

COMMENT ON COLUMN order_item.payment_price IS '支付金额（购买单价*商品数量+运费金额-积分抵扣金额-电子券抵扣金额-用户等级折扣金额）';

COMMENT ON COLUMN order_item.payment_points_price IS '积分抵扣金额';

COMMENT ON COLUMN order_item.payment_coupon_price IS '电子券抵扣金额';

COMMENT ON COLUMN order_item.payment_points IS '支付积分';

COMMENT ON COLUMN order_item.remark IS '备注';



COMMENT ON COLUMN order_item.logistics_id IS '订单物流ID';

COMMENT ON COLUMN order_item.delivery_type IS '发货类型（1、发物流 2、配送 3、虚拟发货）';

COMMENT ON COLUMN order_item.delivery_time IS '发货时间';

CREATE TABLE order_logistics (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    postal_code character varying(10),
    rec_name character varying(50) NOT NULL,
    rec_tel character varying(20) NOT NULL,
    rec_address character varying(255) NOT NULL,
    logistics_code character varying(20),
    logistics_name character varying(50),
    logistics_no character varying(30),
    order_id character varying(32) NOT NULL
);

COMMENT ON COLUMN order_logistics.id IS 'PK';

COMMENT ON COLUMN order_logistics.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN order_logistics.create_time IS '创建时间';

COMMENT ON COLUMN order_logistics.update_time IS '最后更新时间';

COMMENT ON COLUMN order_logistics.postal_code IS '邮编';

COMMENT ON COLUMN order_logistics.rec_name IS '收货人名字';

COMMENT ON COLUMN order_logistics.rec_tel IS '收货人电话号码';

COMMENT ON COLUMN order_logistics.rec_address IS '收货人详细地址';

COMMENT ON COLUMN order_logistics.logistics_code IS '物流公司编码';

COMMENT ON COLUMN order_logistics.logistics_name IS '物流公司名称';

COMMENT ON COLUMN order_logistics.logistics_no IS '物流单号';

COMMENT ON COLUMN order_logistics.order_id IS '订单ID';

CREATE TABLE order_record (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    order_id character varying(32) NOT NULL,
    record_type character varying(32) NOT NULL,
    record_source character varying(32) DEFAULT 'USER'::character varying NOT NULL,
    record_content character varying(500) NOT NULL
);

COMMENT ON COLUMN order_record.id IS 'PK';

COMMENT ON COLUMN order_record.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN order_record.create_time IS '创建时间';

COMMENT ON COLUMN order_record.update_time IS '最后更新时间';

COMMENT ON COLUMN order_record.order_id IS '订单id';

COMMENT ON COLUMN order_record.record_type IS '类型';

COMMENT ON COLUMN order_record.record_source IS '来源';

COMMENT ON COLUMN order_record.record_content IS '内容';

CREATE TABLE order_state_log (
    id character varying(32) NOT NULL,
    order_id character varying(32) NOT NULL,
    from_status character varying(16) NOT NULL,
    to_status character varying(16) NOT NULL,
    event character varying(32) NOT NULL,
    rule_id character varying(16),
    operator_type character varying(16) NOT NULL,
    operator_id character varying(32),
    reason character varying(255),
    request_id character varying(64),
    tenant_id character varying(32) NOT NULL,
    create_time timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE pay_callback_log (
    id character varying(32) NOT NULL,
    pay_no character varying(64),
    channel_code character varying(8) NOT NULL,
    idempotent_key character varying(128) NOT NULL,
    raw_body text,
    sign_valid character varying(2),
    channel_status character varying(32),
    process_status character varying(24) DEFAULT 'RECEIVED'::character varying NOT NULL,
    reject_reason character varying(255),
    tenant_id character varying(36),
    del_flag character varying(2) DEFAULT '0'::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pay_config (
    id character varying(32) DEFAULT ''::character varying NOT NULL,
    create_id character varying(32),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_id character varying(32),
    update_time timestamp without time zone,
    del_flag character varying(2) DEFAULT '0'::bpchar,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    type character varying(2) NOT NULL,
    model character varying(2) NOT NULL,
    app_id character varying(64),
    mch_id character varying(64),
    mch_key character varying(2048) NOT NULL,
    key_path character varying(255),
    private_key_path character varying(255),
    private_cert_path character varying(255),
    cert_serial_no character varying(64),
    api_v3_key character varying(64)
);

COMMENT ON COLUMN pay_config.id IS 'PK';

COMMENT ON COLUMN pay_config.create_id IS '创建者';

COMMENT ON COLUMN pay_config.create_time IS '创建时间';

COMMENT ON COLUMN pay_config.update_id IS '更新者';

COMMENT ON COLUMN pay_config.update_time IS '更新时间';

COMMENT ON COLUMN pay_config.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN pay_config.type IS '类型：1微信支付；2支付宝';

COMMENT ON COLUMN pay_config.model IS '模式：1服务商模式；2普通商户模式';

COMMENT ON COLUMN pay_config.app_id IS '微信支付/支付宝服务商的APPID';

COMMENT ON COLUMN pay_config.mch_id IS '微信支付商户号';

COMMENT ON COLUMN pay_config.mch_key IS '微信支付商户密钥/支付宝私钥';

COMMENT ON COLUMN pay_config.key_path IS '微信支付p12证书的文件路径/支付宝根证书文件路径';

COMMENT ON COLUMN pay_config.private_key_path IS '微信支付apiclient_key.pem证书的文件路径/支付宝公钥证书文件路径';

COMMENT ON COLUMN pay_config.private_cert_path IS '微信支付apiclient_cert.pem证书的文件路径/支付宝应用公钥证书文件路径';

COMMENT ON COLUMN pay_config.cert_serial_no IS '微信支付apiV3证书序列号';

COMMENT ON COLUMN pay_config.api_v3_key IS '微信支付apiV3秘钥';


CREATE TABLE pay_exception (
    id character varying(32) NOT NULL,
    type character varying(24) NOT NULL,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    order_id character varying(32),
    order_no character varying(50),
    pay_order_id character varying(32),
    channel character varying(8),
    channel_txn_id character varying(128),
    amount_minor bigint,
    currency character varying(3),
    callback_log_id character varying(32),
    dedupe_key character varying(160) NOT NULL,
    detail character varying(1000),
    resolution character varying(32),
    resolved_by character varying(32),
    resolved_time timestamp with time zone,
    resolve_reason character varying(255),
    tenant_id character varying(36),
    create_time timestamp with time zone DEFAULT now() NOT NULL,
    update_time timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE pay_order (
    id character varying(32) NOT NULL,
    pay_no character varying(64) NOT NULL,
    order_no character varying(50),
    biz_type character varying(32) NOT NULL,
    biz_id character varying(64),
    channel_code character varying(8) NOT NULL,
    amount numeric(10,2) NOT NULL,
    curr_code character varying(8) DEFAULT 'CNY'::character varying NOT NULL,
    channel_txn_id character varying(128),
    status character varying(24) DEFAULT 'CREATED'::character varying NOT NULL,
    pay_time timestamp without time zone,
    expire_time timestamp without time zone,
    client_ip character varying(64),
    attach jsonb,
    tenant_id character varying(36),
    del_flag character varying(2) DEFAULT '0'::character varying,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    currency character varying(3),
    amount_minor bigint,
    channel_closed boolean DEFAULT false NOT NULL,
    late_paid boolean DEFAULT false NOT NULL,
    duplicate_paid boolean DEFAULT false NOT NULL,
    fail_reason character varying(255),
    closed_time timestamp with time zone,
    state_since timestamp with time zone,
    CONSTRAINT ck_pay_order_status CHECK (((status)::text = ANY ((ARRAY['CREATED'::character varying, 'PAYING'::character varying, 'PAID'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE TABLE pay_order_state_log (
    id character varying(32) NOT NULL,
    pay_order_id character varying(32) NOT NULL,
    pay_no character varying(64),
    from_status character varying(20) NOT NULL,
    to_status character varying(20) NOT NULL,
    event character varying(32) NOT NULL,
    rule_id character varying(16),
    operator_type character varying(16) NOT NULL,
    channel_txn_id character varying(128),
    callback_log_id character varying(32),
    reason character varying(255),
    tenant_id character varying(32) NOT NULL,
    create_time timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE shop_info (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    enable character varying(2) DEFAULT '0'::bpchar NOT NULL,
    phone character varying(15) NOT NULL,
    name character varying(100) NOT NULL,
    img_url character varying(1000) NOT NULL
);

COMMENT ON COLUMN shop_info.id IS 'PK';

COMMENT ON COLUMN shop_info.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN shop_info.create_time IS '创建时间';

COMMENT ON COLUMN shop_info.update_time IS '最后更新时间';

COMMENT ON COLUMN shop_info.enable IS '（1：开启；0：关闭）';

COMMENT ON COLUMN shop_info.phone IS '电话号码';

COMMENT ON COLUMN shop_info.name IS '店铺名';

COMMENT ON COLUMN shop_info.img_url IS '图片';

CREATE TABLE shopping_cart (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    shop_id character varying(32) NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    user_id character varying(32) NOT NULL,
    spu_id character varying(32) NOT NULL,
    sku_id character varying(32) NOT NULL,
    quantity integer NOT NULL,
    spu_name character varying(200),
    add_price numeric(10,2),
    spec_info character varying(200),
    pic_url character varying(500),
    page_type character varying(255)
);

COMMENT ON COLUMN shopping_cart.id IS 'PK';

COMMENT ON COLUMN shopping_cart.shop_id IS '店铺Id';

COMMENT ON COLUMN shopping_cart.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN shopping_cart.create_time IS '创建时间';

COMMENT ON COLUMN shopping_cart.update_time IS '最后更新时间';

COMMENT ON COLUMN shopping_cart.user_id IS '用户编号';

COMMENT ON COLUMN shopping_cart.spu_id IS '商品SPU';

COMMENT ON COLUMN shopping_cart.sku_id IS '商品SKU';

COMMENT ON COLUMN shopping_cart.quantity IS '数量';

COMMENT ON COLUMN shopping_cart.spu_name IS '加入时的spu名字';

COMMENT ON COLUMN shopping_cart.add_price IS '加入时价格';

COMMENT ON COLUMN shopping_cart.spec_info IS '加入时的规格信息';

COMMENT ON COLUMN shopping_cart.pic_url IS '图片';

COMMENT ON COLUMN shopping_cart.page_type IS '页面来源: nomarl（普通商品）、seckill（秒杀商品）、flash（限时折扣）、newcomer（新人商品）、distribution （分销）';

CREATE TABLE stock_hold (
    id character varying(32) NOT NULL,
    order_id character varying(32) NOT NULL,
    order_item_id character varying(32),
    sku_id character varying(32) NOT NULL,
    qty integer NOT NULL,
    status character varying(12) DEFAULT 'HOLD'::character varying NOT NULL,
    expire_time timestamp with time zone NOT NULL,
    release_reason character varying(32),
    tenant_id character varying(32),
    create_time timestamp with time zone DEFAULT now() NOT NULL,
    update_time timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_stock_hold_qty CHECK ((qty > 0)),
    CONSTRAINT ck_stock_hold_status CHECK (((status)::text = ANY ((ARRAY['HOLD'::character varying, 'CONFIRMED'::character varying, 'RELEASED'::character varying])::text[])))
);

CREATE TABLE user_address (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    user_id character varying(32) NOT NULL,
    user_name character varying(50) NOT NULL,
    postal_code character varying(50),
    province_name character varying(50) NOT NULL,
    city_name character varying(50) NOT NULL,
    county_name character varying(50) NOT NULL,
    detail_info character varying(50) NOT NULL,
    area_id character varying(20) NOT NULL,
    tel_num character varying(50) NOT NULL,
    is_default character varying(2),
    longitude character varying(64),
    latitude character varying(64)
);

COMMENT ON COLUMN user_address.id IS 'PK';

COMMENT ON COLUMN user_address.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN user_address.create_time IS '创建时间';

COMMENT ON COLUMN user_address.update_time IS '最后更新时间';

COMMENT ON COLUMN user_address.user_id IS '用户id';

COMMENT ON COLUMN user_address.user_name IS '收货人名字';

COMMENT ON COLUMN user_address.postal_code IS '邮编';

COMMENT ON COLUMN user_address.province_name IS '省名';

COMMENT ON COLUMN user_address.city_name IS '市名';

COMMENT ON COLUMN user_address.county_name IS '区名';

COMMENT ON COLUMN user_address.detail_info IS '详情地址';

COMMENT ON COLUMN user_address.area_id IS '地区ID';

COMMENT ON COLUMN user_address.tel_num IS '电话号码';

COMMENT ON COLUMN user_address.is_default IS '是否默认 1是0否';

CREATE TABLE user_info (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    user_code integer NOT NULL,
    app_type character varying(10) NOT NULL,
    app_id character varying(32),
    phone character varying(15),
    password character varying(255),
    nick_name character varying(100),
    sex character varying(2),
    headimg_url character varying(1000),
    city character varying(64),
    country character varying(64),
    province character varying(64),
    version integer DEFAULT 0,
    address character varying(50),
    last_login_ip character varying(50),
    register_ip character varying(50),
    device_type character varying(32),
    device_info character varying(500),
    last_login_time2 timestamp without time zone,
    black_flag character varying(2) DEFAULT '0'::character varying,
    email character varying(128),
    username character varying(32)
);

COMMENT ON COLUMN user_info.id IS 'PK';

COMMENT ON COLUMN user_info.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN user_info.create_time IS '创建时间';

COMMENT ON COLUMN user_info.update_time IS '最后更新时间';

COMMENT ON COLUMN user_info.user_code IS '用户编码';

COMMENT ON COLUMN user_info.app_type IS '来源应用（MA：小程序；H5：普通H5；H5-WX：微信H5；APP：app；H5-PC：PC端H5）';

COMMENT ON COLUMN user_info.app_id IS '来源应用id';

COMMENT ON COLUMN user_info.phone IS '手机号码';

COMMENT ON COLUMN user_info.password IS '密码';

COMMENT ON COLUMN user_info.nick_name IS '昵称';

COMMENT ON COLUMN user_info.sex IS '性别（1：男，2：女，0：未知）';

COMMENT ON COLUMN user_info.headimg_url IS '头像';

COMMENT ON COLUMN user_info.city IS '所在城市';

COMMENT ON COLUMN user_info.country IS '所在国家';

COMMENT ON COLUMN user_info.province IS '所在省份';

COMMENT ON COLUMN user_info.version IS '版本号';

COMMENT ON COLUMN user_info.address IS '地址';

COMMENT ON COLUMN user_info.last_login_ip IS '最后登录IP地址';

COMMENT ON COLUMN user_info.register_ip IS '注册IP地址';

COMMENT ON COLUMN user_info.device_type IS '设备类型（iOS/Android/PC/WeChat等）';

COMMENT ON COLUMN user_info.device_info IS '设备信息（设备型号、操作系统版本等）';

COMMENT ON COLUMN user_info.last_login_time2 IS '最后登录时间';

COMMENT ON COLUMN user_info.black_flag IS '黑名单标志：0 正常（默认）；1 拉黑（上层登录/下单拦截读取本列）';

COMMENT ON COLUMN user_info.email IS '邮箱（登录标识之一，入库前统一 trim + 小写）';

ALTER TABLE ONLY global_hot_word ALTER COLUMN id SET DEFAULT nextval('global_hot_word_id_seq'::regclass);

ALTER TABLE ONLY global_hot_word
    ADD CONSTRAINT global_hot_word_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_brand
    ADD CONSTRAINT goods_brand_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_category
    ADD CONSTRAINT goods_category_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_import_task
    ADD CONSTRAINT goods_import_task_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_parameter
    ADD CONSTRAINT goods_parameter_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_parameter_template
    ADD CONSTRAINT goods_parameter_template_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_parameter_value
    ADD CONSTRAINT goods_parameter_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_sku
    ADD CONSTRAINT goods_sku_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_sku_spec_value
    ADD CONSTRAINT goods_sku_spec_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_spec
    ADD CONSTRAINT goods_spec_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_spec_value
    ADD CONSTRAINT goods_spec_value_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_spu
    ADD CONSTRAINT goods_spu_pkey PRIMARY KEY (id);

ALTER TABLE ONLY goods_spu_spec
    ADD CONSTRAINT goods_spu_spec_pkey PRIMARY KEY (id);

ALTER TABLE ONLY mall_config
    ADD CONSTRAINT mall_config_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_group
    ADD CONSTRAINT material_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material
    ADD CONSTRAINT material_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_info
    ADD CONSTRAINT order_info_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_logistics
    ADD CONSTRAINT order_logistics_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_record
    ADD CONSTRAINT order_record_pkey PRIMARY KEY (id);

ALTER TABLE ONLY order_state_log
    ADD CONSTRAINT order_state_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pay_config
    ADD CONSTRAINT pay_config_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pay_exception
    ADD CONSTRAINT pay_exception_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pay_order_state_log
    ADD CONSTRAINT pay_order_state_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY pay_callback_log
    ADD CONSTRAINT pk_pay_callback_log PRIMARY KEY (id);

ALTER TABLE ONLY pay_order
    ADD CONSTRAINT pk_pay_order PRIMARY KEY (id);

ALTER TABLE ONLY shop_info
    ADD CONSTRAINT shop_info_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shopping_cart
    ADD CONSTRAINT shopping_cart_pkey PRIMARY KEY (id);

ALTER TABLE ONLY stock_hold
    ADD CONSTRAINT stock_hold_pkey PRIMARY KEY (id);

ALTER TABLE ONLY user_address
    ADD CONSTRAINT user_address_pkey PRIMARY KEY (id);

ALTER TABLE ONLY user_info
    ADD CONSTRAINT user_info_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX global_hot_word_word_idx ON global_hot_word USING btree (word) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX goods_brand_tenant_id_idx ON goods_brand USING btree (tenant_id);

CREATE INDEX goods_category_parent_id_sort_idx ON goods_category USING btree (parent_id, sort);

CREATE INDEX goods_category_tenant_id_idx ON goods_category USING btree (tenant_id);

CREATE INDEX goods_import_task_list_aabbdc1c4063ffbbf97ae46eb45e5f2b ON goods_import_task USING btree (tenant_id, del_flag, status, create_time DESC);

CREATE INDEX goods_import_task_running_aabbdc1c4063ffbbf97ae46eb45e5f2b ON goods_import_task USING btree (tenant_id, status) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX goods_parameter_tenant_id_idx ON goods_parameter USING btree (tenant_id);

CREATE INDEX goods_parameter_value_tenant_id_idx ON goods_parameter_value USING btree (tenant_id);

CREATE UNIQUE INDEX goods_sku_spec_value_active_uk ON goods_sku_spec_value USING btree (tenant_id, sku_id, spec_value_id) WHERE (del_flag = '0'::bpchar);

CREATE INDEX goods_sku_spec_value_tenant_id_idx ON goods_sku_spec_value USING btree (tenant_id);

CREATE INDEX goods_sku_spu_id_tenant_id_idx ON goods_sku USING btree (spu_id, tenant_id);

CREATE INDEX goods_sku_tenant_id_idx ON goods_sku USING btree (tenant_id);

CREATE INDEX goods_spec_tenant_id_idx ON goods_spec USING btree (tenant_id);

CREATE UNIQUE INDEX goods_spec_tenant_id_name_idx ON goods_spec USING btree (tenant_id, name) WHERE ((del_flag)::text = '0'::text);

CREATE UNIQUE INDEX goods_spec_value_spec_id_name_tenant_id_idx ON goods_spec_value USING btree (spec_id, name, tenant_id) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX goods_spec_value_tenant_id_idx ON goods_spec_value USING btree (tenant_id);

CREATE INDEX goods_spu_category_second_idx ON goods_spu USING btree (category_second);

CREATE UNIQUE INDEX goods_spu_spec_active_uk ON goods_spu_spec USING btree (tenant_id, spu_id, spec_id) WHERE (del_flag = '0'::bpchar);

CREATE INDEX goods_spu_spec_tenant_id_idx ON goods_spu_spec USING btree (tenant_id);

CREATE INDEX goods_spu_tenant_id_idx ON goods_spu USING btree (tenant_id);

CREATE UNIQUE INDEX goods_spu_tenant_id_spu_code_idx ON goods_spu USING btree (tenant_id, spu_code) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX idx_goods_spu_seo_sitemap ON goods_spu USING btree (shelf, verify_status, del_flag, update_time);

CREATE INDEX idx_order_info_bargain_paid ON order_info USING btree (order_type, is_pay, status, payment_time) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX idx_order_state_log_order ON order_state_log USING btree (order_id, create_time);

CREATE INDEX idx_order_state_log_order_request ON order_state_log USING btree (order_id, request_id, create_time DESC);

CREATE INDEX idx_pay_callback_pay_no ON pay_callback_log USING btree (pay_no);

CREATE INDEX idx_pay_exception_order ON pay_exception USING btree (order_id);

CREATE INDEX idx_pay_exception_status_time ON pay_exception USING btree (status, create_time);

CREATE INDEX idx_pay_order_biz ON pay_order USING btree (biz_type, biz_id);

CREATE INDEX idx_pay_order_order_no ON pay_order USING btree (order_no);

CREATE INDEX idx_pay_order_state_log_po ON pay_order_state_log USING btree (pay_order_id, create_time);

CREATE INDEX idx_pay_order_status_time ON pay_order USING btree (status, create_time);

CREATE INDEX idx_pay_order_txn ON pay_order USING btree (channel_code, channel_txn_id);

CREATE INDEX idx_spu_category_listing ON goods_spu USING btree (category_second, shelf, verify_status, del_flag, sort);

CREATE INDEX idx_stock_hold_order ON stock_hold USING btree (order_id);

CREATE INDEX idx_stock_hold_status_expire ON stock_hold USING btree (status, expire_time);

CREATE INDEX mall_config_config_key_idx ON mall_config USING btree (config_key);

CREATE UNIQUE INDEX mall_config_tenant_id_config_key_idx ON mall_config USING btree (tenant_id, config_key) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX material_group_tenant_id_idx ON material_group USING btree (tenant_id);

CREATE INDEX material_tenant_id_idx ON material USING btree (tenant_id);

CREATE UNIQUE INDEX order_info_order_no_idx ON order_info USING btree (order_no) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX order_info_pending_cancel_idx ON order_info USING btree (create_time) WHERE (((is_pay)::text = '0'::text) AND (status IS NULL) AND ((del_flag)::text = '0'::text));

CREATE INDEX order_info_tenant_id_order_no_idx ON order_info USING btree (tenant_id, order_no);

CREATE INDEX order_info_user_id_create_time_idx ON order_info USING btree (user_id, create_time DESC);

CREATE INDEX order_item_order_id_idx ON order_item USING btree (order_id);

CREATE INDEX order_item_spu_id_idx ON order_item USING btree (spu_id);

CREATE INDEX order_item_tenant_id_idx ON order_item USING btree (tenant_id);

CREATE INDEX order_logistics_tenant_id_idx ON order_logistics USING btree (tenant_id);

CREATE INDEX order_logistics_tenant_id_logistics_code_logistics_no_idx ON order_logistics USING btree (tenant_id, logistics_code, logistics_no);

CREATE INDEX order_record_tenant_id_order_id_record_type_idx ON order_record USING btree (tenant_id, order_id, record_type);

CREATE INDEX pay_config_tenant_id_idx ON pay_config USING btree (tenant_id);

CREATE UNIQUE INDEX pay_config_tenant_id_type_appid_idx ON pay_config USING btree (tenant_id, type, app_id) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX shop_info_tenant_id_idx ON shop_info USING btree (tenant_id);

CREATE UNIQUE INDEX shop_info_tenant_id_name_idx ON shop_info USING btree (tenant_id, name) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX shopping_cart_tenant_id_idx ON shopping_cart USING btree (tenant_id);

CREATE UNIQUE INDEX shopping_cart_tenant_id_user_id_spu_id_sku_id_idx ON shopping_cart USING btree (tenant_id, user_id, spu_id, sku_id) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX shopping_cart_user_id_tenant_id_update_time_idx ON shopping_cart USING btree (user_id, tenant_id, update_time);

CREATE UNIQUE INDEX uk_pay_callback_idem ON pay_callback_log USING btree (channel_code, idempotent_key);

CREATE UNIQUE INDEX uk_pay_exception_dedupe ON pay_exception USING btree (dedupe_key);

CREATE UNIQUE INDEX uk_pay_order_pay_no ON pay_order USING btree (pay_no);

CREATE UNIQUE INDEX uk_stock_hold_item ON stock_hold USING btree (order_item_id) WHERE (order_item_id IS NOT NULL);

CREATE INDEX user_address_tenant_id_idx ON user_address USING btree (tenant_id);

CREATE INDEX user_info_device_type_idx ON user_info USING btree (device_type);

CREATE UNIQUE INDEX user_info_email_uidx ON user_info USING btree (email) WHERE ((email IS NOT NULL) AND ((del_flag)::text = '0'::text));

CREATE INDEX user_info_last_login_ip_idx ON user_info USING btree (last_login_ip);

CREATE INDEX user_info_phone_idx ON user_info USING btree (phone);

CREATE INDEX user_info_register_ip_idx ON user_info USING btree (register_ip);

CREATE INDEX user_info_tenant_id_idx ON user_info USING btree (tenant_id);

CREATE INDEX user_info_user_code_idx ON user_info USING btree (user_code);

CREATE TRIGGER trg_state_guard BEFORE UPDATE OF status, is_pay ON order_info FOR EACH ROW EXECUTE FUNCTION public.fn_state_guard();

CREATE TRIGGER trg_state_guard BEFORE UPDATE OF status ON pay_order FOR EACH ROW EXECUTE FUNCTION public.fn_state_guard();

ALTER TABLE ONLY goods_spu
    ADD CONSTRAINT fk_goods_spu__category_first FOREIGN KEY (category_first) REFERENCES goods_category(id) NOT VALID;

ALTER TABLE ONLY order_info
    ADD CONSTRAINT fk_order_info__user_id FOREIGN KEY (user_id) REFERENCES user_info(id) NOT VALID;

COMMENT ON COLUMN user_info.username IS '用户名（可空；租户内未删除账号唯一，不区分大小写）';

CREATE UNIQUE INDEX uk_user_info_username ON user_info USING btree (tenant_id, lower((username)::text)) WHERE ((username IS NOT NULL) AND ((del_flag)::text = '0'::text));

CREATE TABLE banner (
    id          varchar(32)  PRIMARY KEY,
    tenant_id   varchar(36),
    title       varchar(64)  NOT NULL,
    image_url   varchar(500) NOT NULL,
    link_url    varchar(500),
    sort        integer      NOT NULL DEFAULT 0,
    enable      char(1)      NOT NULL DEFAULT '1',
    del_flag    char(1)      NOT NULL DEFAULT '0',
    create_time timestamp    NOT NULL DEFAULT now(),
    update_time timestamp    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  banner IS '首页轮播图';
COMMENT ON COLUMN banner.title IS '标题（后台识别用，H5 不展示）';
COMMENT ON COLUMN banner.image_url IS '图片地址';
COMMENT ON COLUMN banner.link_url IS '点击跳转地址；空 = 不跳转';
COMMENT ON COLUMN banner.sort IS '排序，小的在前';
COMMENT ON COLUMN banner.enable IS '1 启用 / 0 停用';

CREATE INDEX idx_banner_enable_sort ON banner (enable, sort) WHERE del_flag = '0';

INSERT INTO shop_info (id, enable, name, phone, img_url, tenant_id, del_flag, create_time, update_time) VALUES ('1', '1', '智流商城', '', '', '1', '0', '2026-10-20 00:00:00', '2026-10-20 00:00:00');
