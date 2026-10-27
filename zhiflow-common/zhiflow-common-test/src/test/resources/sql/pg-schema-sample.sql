--
-- PostgreSQL database dump
--

\restrict HSImYtgEUcc9tkVVE3jlBrfLn7osSPGmOERCU0lr7W1IFensPTi5468YHMyekMC

-- Dumped from database version 18.4 (Debian 18.4-1.pgdg12+1)
-- Dumped by pg_dump version 18.4 (Debian 18.4-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: coupon_user; Type: TABLE; Schema: zhiflow_mall_1; Owner: -
--

CREATE TABLE zhiflow_mall_1.coupon_user (
    id character varying(32) NOT NULL,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    shop_id character varying(32) NOT NULL,
    del_flag character varying(2) DEFAULT '0'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_id character varying(32),
    coupon_id character varying(32) NOT NULL,
    user_id character varying(32) NOT NULL,
    coupon_code integer NOT NULL,
    valid_begin_time timestamp without time zone,
    valid_end_time timestamp without time zone,
    status character varying(2) DEFAULT '0'::bpchar,
    used_time timestamp without time zone,
    name character varying(30) NOT NULL,
    type character varying(2),
    premise_amount numeric(10,2),
    reduce_amount numeric(10,2),
    discount numeric(10,1),
    suit_type character varying(2),
    receive_type character varying(2) DEFAULT '1'::bpchar NOT NULL
);


--
-- Name: COLUMN coupon_user.id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.id IS 'PK';


--
-- Name: COLUMN coupon_user.shop_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.shop_id IS '店铺Id';


--
-- Name: COLUMN coupon_user.del_flag; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';


--
-- Name: COLUMN coupon_user.create_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.create_time IS '创建时间';


--
-- Name: COLUMN coupon_user.update_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.update_time IS '最后更新时间';


--
-- Name: COLUMN coupon_user.create_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.create_id IS '创建者ID';


--
-- Name: COLUMN coupon_user.coupon_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.coupon_id IS '电子券ID';


--
-- Name: COLUMN coupon_user.user_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.user_id IS '用户id';


--
-- Name: COLUMN coupon_user.coupon_code; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.coupon_code IS '电子券码';


--
-- Name: COLUMN coupon_user.valid_begin_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.valid_begin_time IS '有效开始时间';


--
-- Name: COLUMN coupon_user.valid_end_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.valid_end_time IS '有效结束时间';


--
-- Name: COLUMN coupon_user.status; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.status IS '状态0、未使用；1、已使用';


--
-- Name: COLUMN coupon_user.used_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.used_time IS '使用时间';


--
-- Name: COLUMN coupon_user.name; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.name IS '名称';


--
-- Name: COLUMN coupon_user.type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.type IS '类型1、代金券；2：折扣券';


--
-- Name: COLUMN coupon_user.premise_amount; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.premise_amount IS '订单金额满多少可使用';


--
-- Name: COLUMN coupon_user.reduce_amount; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.reduce_amount IS '减免金额（代金券特有）';


--
-- Name: COLUMN coupon_user.discount; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.discount IS '折扣率（折扣券特有）';


--
-- Name: COLUMN coupon_user.suit_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.suit_type IS '适用类型1、全部商品；2、指定商品可用';


--
-- Name: COLUMN coupon_user.receive_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.coupon_user.receive_type IS '领取方式 ->1：手动，2：自动';


--
-- Name: goods_spu; Type: TABLE; Schema: zhiflow_mall_1; Owner: -
--

CREATE TABLE zhiflow_mall_1.goods_spu (
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
    category_shop_first character varying(32),
    category_shop_second character varying(32),
    category_shop_third character varying(32),
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
    points_give_switch character varying(2),
    points_give_num integer,
    points_deduct_switch character varying(2),
    vip_discount_switch character varying(2) NOT NULL,
    points_deduct_scale integer,
    points_deduct_amount numeric(10,2),
    freight_type character varying(2) NOT NULL,
    freight_amount numeric(10,2),
    freight_templat_id character varying(32),
    verify_status character varying(2) DEFAULT '0'::bpchar NOT NULL,
    verify_detail character varying(50),
    user_grade_discount_switch character varying(2) NOT NULL,
    supplier_id character varying(32),
    supplier_spu_id character varying(32),
    sale_type character varying(2) NOT NULL,
    brand_id character varying(32),
    brand_label character varying(2048),
    is_real_info boolean NOT NULL,
    label character varying(100),
    parameter_json text,
    is_distribution boolean NOT NULL,
    commission_rule character varying(1),
    platform character varying(50) DEFAULT '["H5","APP","MA","H5-PC","H5-WX"]'::character varying NOT NULL,
    collect_num integer DEFAULT 0,
    grade numeric(3,1),
    view_num integer DEFAULT 0,
    prescription character varying(2) DEFAULT '0'::bpchar NOT NULL,
    extended text,
    commission_pool_rate numeric(5,2),
    vip_discount numeric(5,2),
    seo_slug character varying(160),
    seo_title character varying(200),
    seo_description character varying(500),
    seo_keywords character varying(500),
    seo_indexable boolean DEFAULT true NOT NULL,
    seo_slug_updated_at timestamp without time zone,
    questionnaire_id character varying(32),
    is_subsidy character varying(2) DEFAULT '0'::character varying,
    month_sale_num integer DEFAULT 0,
    high_commission boolean DEFAULT false NOT NULL,
    approval_no character varying(64),
    stock_warn_line integer,
    distribution_remark character varying(500)
);


--
-- Name: COLUMN goods_spu.id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.id IS 'PK';


--
-- Name: COLUMN goods_spu.shop_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.shop_id IS '店铺Id';


--
-- Name: COLUMN goods_spu.spu_code; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.spu_code IS 'spu编码';


--
-- Name: COLUMN goods_spu.name; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.name IS 'spu名字';


--
-- Name: COLUMN goods_spu.sell_point; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.sell_point IS '卖点';


--
-- Name: COLUMN goods_spu.description; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.description IS '描述';


--
-- Name: COLUMN goods_spu.category_first; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.category_first IS '商城商品一级分类ID';


--
-- Name: COLUMN goods_spu.category_second; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.category_second IS '商城商品二级分类ID';


--
-- Name: COLUMN goods_spu.category_third; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.category_third IS '商城商品三级分类ID';


--
-- Name: COLUMN goods_spu.category_shop_first; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.category_shop_first IS '店铺商品一级分类ID';


--
-- Name: COLUMN goods_spu.category_shop_second; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.category_shop_second IS '店铺商品二级分类ID';


--
-- Name: COLUMN goods_spu.category_shop_third; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.category_shop_third IS '店铺商品三级分类ID';


--
-- Name: COLUMN goods_spu.video_url; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.video_url IS '商品视频地址';


--
-- Name: COLUMN goods_spu.pic_urls; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.pic_urls IS '商品图片';


--
-- Name: COLUMN goods_spu.shelf; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.shelf IS '是否上架（1是 0否）';


--
-- Name: COLUMN goods_spu.sort; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.sort IS '排序字段';


--
-- Name: COLUMN goods_spu.price_down; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.price_down IS '最低价';


--
-- Name: COLUMN goods_spu.price_up; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.price_up IS '最高价';


--
-- Name: COLUMN goods_spu.sale_num; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.sale_num IS '销量';


--
-- Name: COLUMN goods_spu.create_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.create_time IS '创建时间';


--
-- Name: COLUMN goods_spu.update_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.update_time IS '最后更新时间';


--
-- Name: COLUMN goods_spu.spec_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.spec_type IS '0统一规格；1多规格';


--
-- Name: COLUMN goods_spu.del_flag; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';


--
-- Name: COLUMN goods_spu.points_give_switch; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.points_give_switch IS '积分赠送开关（1开 0关）';


--
-- Name: COLUMN goods_spu.points_give_num; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.points_give_num IS '积分赠送数量';


--
-- Name: COLUMN goods_spu.points_deduct_switch; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.points_deduct_switch IS '积分抵扣开关（1开 0关）';


--
-- Name: COLUMN goods_spu.vip_discount_switch; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.vip_discount_switch IS '参与vip折扣开关（1开 0关）';


--
-- Name: COLUMN goods_spu.points_deduct_scale; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.points_deduct_scale IS '积分抵扣商品金额比例（0~100）';


--
-- Name: COLUMN goods_spu.points_deduct_amount; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.points_deduct_amount IS '1积分数可抵多少元';


--
-- Name: COLUMN goods_spu.freight_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.freight_type IS '运费类型（0：包邮；1：固定邮费；2：运费模板）';


--
-- Name: COLUMN goods_spu.freight_amount; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.freight_amount IS '运费金额（固定邮费必填）';


--
-- Name: COLUMN goods_spu.freight_templat_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.freight_templat_id IS '运费模板ID（运费模板必填）';


--
-- Name: COLUMN goods_spu.verify_status; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.verify_status IS '审核状态（0审核中 1审核通过 2审核不通过）';


--
-- Name: COLUMN goods_spu.verify_detail; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.verify_detail IS '审核明细';


--
-- Name: COLUMN goods_spu.user_grade_discount_switch; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.user_grade_discount_switch IS '参与用户等级折扣开关（1开 0关）';


--
-- Name: COLUMN goods_spu.supplier_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.supplier_id IS '供应商ID';


--
-- Name: COLUMN goods_spu.supplier_spu_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.supplier_spu_id IS '供应商spuID';


--
-- Name: COLUMN goods_spu.sale_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.sale_type IS '类型（1：默认；2：自营）';


--
-- Name: COLUMN goods_spu.brand_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.brand_id IS '品牌ID';


--
-- Name: COLUMN goods_spu.brand_label; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.brand_label IS '商品标签';


--
-- Name: COLUMN goods_spu.label; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.label IS '排序';


--
-- Name: COLUMN goods_spu.parameter_json; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.parameter_json IS '参数json';


--
-- Name: COLUMN goods_spu.is_distribution; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.is_distribution IS '是否参与分销';


--
-- Name: COLUMN goods_spu.commission_rule; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.commission_rule IS '佣金规则1:默认规则2：单独设置';


--
-- Name: COLUMN goods_spu.collect_num; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.collect_num IS '收藏数';


--
-- Name: COLUMN goods_spu.grade; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.grade IS '评分';


--
-- Name: COLUMN goods_spu.view_num; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.view_num IS '浏览量';


--
-- Name: COLUMN goods_spu.commission_pool_rate; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.commission_pool_rate IS '毛利率分销-总佣金比例池(%)';


--
-- Name: COLUMN goods_spu.vip_discount; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.vip_discount IS 'vip折扣，0.9代表9折';


--
-- Name: COLUMN goods_spu.is_subsidy; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.is_subsidy IS '百亿补贴打标：0否 1是。纯展示角标，不参与算价，不校验补贴价低于原价';


--
-- Name: COLUMN goods_spu.month_sale_num; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.month_sale_num IS '近30天销量，由 MonthSaleAggregateJob 每日物化刷新；sale_num 是累计销量，二者不同';


--
-- Name: COLUMN goods_spu.high_commission; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.high_commission IS '高佣专区圈定（N8）：admin PUT /distribution-goods/high-commission 置位，C 端 /distributiongoods/high-commission 按 =true 分页';


--
-- Name: COLUMN goods_spu.approval_no; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.approval_no IS '批准文号（管理端展示+可编辑）';


--
-- Name: COLUMN goods_spu.stock_warn_line; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.stock_warn_line IS '库存预警线阈值，聚合SKU库存低于此值预警；空则兜底默认20';


--
-- Name: COLUMN goods_spu.distribution_remark; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.goods_spu.distribution_remark IS '推广中心商品运营备注（p10 更多操作）';


--
-- Name: page_devise; Type: TABLE; Schema: zhiflow_mall_1; Owner: -
--

CREATE TABLE zhiflow_mall_1.page_devise (
    id character varying(32) NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    tenant_id character varying(36) DEFAULT '1'::character varying,
    page_type character varying(300),
    page_name character varying(200) DEFAULT ''::character varying,
    page_component text,
    page_img character varying(500),
    page_desc character varying(100),
    enable character varying(2) NOT NULL,
    shop_id character varying(32) DEFAULT '-1'::character varying,
    client_type character varying(10) DEFAULT '-1'::bpchar,
    app_id character varying(32) DEFAULT '-1'::character varying,
    del_flag character(2) DEFAULT '0'::bpchar NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    lock_by character varying(64),
    lock_time timestamp without time zone,
    visible_scope character varying(1) DEFAULT '1'::character varying NOT NULL
);


--
-- Name: COLUMN page_devise.id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.id IS 'PK';


--
-- Name: COLUMN page_devise.create_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.create_time IS '创建时间';


--
-- Name: COLUMN page_devise.update_time; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.update_time IS '最后更新时间';


--
-- Name: COLUMN page_devise.page_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.page_type IS '页面类型，存各个页面的路径';


--
-- Name: COLUMN page_devise.page_name; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.page_name IS '页面名字，分享时用到';


--
-- Name: COLUMN page_devise.page_component; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.page_component IS '页面内容';


--
-- Name: COLUMN page_devise.page_img; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.page_img IS '页面图片，分享时用到';


--
-- Name: COLUMN page_devise.page_desc; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.page_desc IS '页面描述，分享时用到';


--
-- Name: COLUMN page_devise.enable; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.enable IS '是否启用1、是；0否';


--
-- Name: COLUMN page_devise.shop_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.shop_id IS '店铺Id（-1为商城页面）';


--
-- Name: COLUMN page_devise.client_type; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.client_type IS '应用类型（H5、APP、MA、H5-PC）';


--
-- Name: COLUMN page_devise.app_id; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.app_id IS '小程序、公众号ID';


--
-- Name: COLUMN page_devise.visible_scope; Type: COMMENT; Schema: zhiflow_mall_1; Owner: -
--

COMMENT ON COLUMN zhiflow_mall_1.page_devise.visible_scope IS '页面人群：1=专属版(默认/存量) 0=大众版；与 user_info.visible_scope 同词汇';


--
-- Name: coupon_user coupon_user_pkey; Type: CONSTRAINT; Schema: zhiflow_mall_1; Owner: -
--

ALTER TABLE ONLY zhiflow_mall_1.coupon_user
    ADD CONSTRAINT coupon_user_pkey PRIMARY KEY (id);


--
-- Name: goods_spu goods_spu_pkey; Type: CONSTRAINT; Schema: zhiflow_mall_1; Owner: -
--

ALTER TABLE ONLY zhiflow_mall_1.goods_spu
    ADD CONSTRAINT goods_spu_pkey PRIMARY KEY (id);


--
-- Name: page_devise page_devise_pkey; Type: CONSTRAINT; Schema: zhiflow_mall_1; Owner: -
--

ALTER TABLE ONLY zhiflow_mall_1.page_devise
    ADD CONSTRAINT page_devise_pkey PRIMARY KEY (id);


--
-- Name: coupon_user_coupon_code_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX coupon_user_coupon_code_idx ON zhiflow_mall_1.coupon_user USING btree (coupon_code);


--
-- Name: coupon_user_coupon_code_idx1; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE UNIQUE INDEX coupon_user_coupon_code_idx1 ON zhiflow_mall_1.coupon_user USING btree (coupon_code);


--
-- Name: coupon_user_tenant_id_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX coupon_user_tenant_id_idx ON zhiflow_mall_1.coupon_user USING btree (tenant_id);


--
-- Name: coupon_user_user_id_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX coupon_user_user_id_idx ON zhiflow_mall_1.coupon_user USING btree (user_id);


--
-- Name: goods_spu_category_second_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX goods_spu_category_second_idx ON zhiflow_mall_1.goods_spu USING btree (category_second);


--
-- Name: goods_spu_tenant_id_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX goods_spu_tenant_id_idx ON zhiflow_mall_1.goods_spu USING btree (tenant_id);


--
-- Name: goods_spu_tenant_id_spu_code_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE UNIQUE INDEX goods_spu_tenant_id_spu_code_idx ON zhiflow_mall_1.goods_spu USING btree (tenant_id, spu_code);


--
-- Name: idx_goods_spu_seo_sitemap; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX idx_goods_spu_seo_sitemap ON zhiflow_mall_1.goods_spu USING btree (shelf, verify_status, del_flag, update_time);


--
-- Name: idx_spu_category_listing; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX idx_spu_category_listing ON zhiflow_mall_1.goods_spu USING btree (category_second, shelf, verify_status, del_flag, sort);


--
-- Name: page_devise_shop_id_client_type_page_type_enable_app_id_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX page_devise_shop_id_client_type_page_type_enable_app_id_idx ON zhiflow_mall_1.page_devise USING btree (shop_id, client_type, page_type, enable, app_id);


--
-- Name: page_devise_tenant_id_idx; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE INDEX page_devise_tenant_id_idx ON zhiflow_mall_1.page_devise USING btree (tenant_id);


--
-- Name: uk_goods_spu_seo_slug; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE UNIQUE INDEX uk_goods_spu_seo_slug ON zhiflow_mall_1.goods_spu USING btree (seo_slug) WHERE ((seo_slug IS NOT NULL) AND ((seo_slug)::text <> ''::text) AND ((del_flag)::text = '0'::text));


--
-- Name: uk_page_devise_enabled; Type: INDEX; Schema: zhiflow_mall_1; Owner: -
--

CREATE UNIQUE INDEX uk_page_devise_enabled ON zhiflow_mall_1.page_devise USING btree (shop_id, client_type, page_type, app_id, visible_scope) WHERE ((enable)::text = '1'::text);


--
-- PostgreSQL database dump complete
--

\unrestrict HSImYtgEUcc9tkVVE3jlBrfLn7osSPGmOERCU0lr7W1IFensPTi5468YHMyekMC

