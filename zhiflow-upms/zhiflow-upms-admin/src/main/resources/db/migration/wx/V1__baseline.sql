-- 智流商城数据库基线（zhiflow_wx）。
-- 首次启动时由迁移器在 search_path = 本 schema 下执行一次；之后的结构变更写 V2、V3……。
-- 结构取自裁剪后保留的表与实体实际映射的列；种子不含任何真实用户、订单与口令哈希。

CREATE FUNCTION on_update_current_timestamp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.update_time = now();
    RETURN NEW;
END;
$$;

CREATE TABLE wx_app (
    id character varying(32) DEFAULT ''::character varying NOT NULL,
    create_id character varying(32),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_id character varying(32),
    update_time timestamp without time zone,
    del_flag character varying(2) DEFAULT '0'::bpchar,
    tenant_id character varying(32) NOT NULL,
    app_type character varying(2),
    secret character varying(64),
    token character varying(64),
    aes_key character varying(64),
    name character varying(50),
    logo character varying(500),
    remarks character varying(255)
);

COMMENT ON TABLE wx_app IS '微信应用';

COMMENT ON COLUMN wx_app.id IS '应用ID';

COMMENT ON COLUMN wx_app.create_id IS '创建者';

COMMENT ON COLUMN wx_app.create_time IS '创建时间';

COMMENT ON COLUMN wx_app.update_id IS '更新者';

COMMENT ON COLUMN wx_app.update_time IS '更新时间';

COMMENT ON COLUMN wx_app.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN wx_app.tenant_id IS '所属租户';

COMMENT ON COLUMN wx_app.app_type IS '应用类型(1:小程序，2:公众号)';

COMMENT ON COLUMN wx_app.secret IS '应用密钥';

COMMENT ON COLUMN wx_app.token IS 'token';

COMMENT ON COLUMN wx_app.aes_key IS 'EncodingAESKey';

COMMENT ON COLUMN wx_app.name IS '微信号名称';

COMMENT ON COLUMN wx_app.logo IS 'logo';

COMMENT ON COLUMN wx_app.remarks IS '备注信息';

CREATE TABLE wx_user (
    id character varying(32) DEFAULT ''::character varying NOT NULL,
    create_id character varying(32),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_id character varying(32),
    update_time timestamp without time zone,
    del_flag character varying(2) DEFAULT '0'::bpchar,
    tenant_id character varying(32) NOT NULL,
    app_id character varying(32) NOT NULL,
    app_type character varying(2),
    open_id character varying(64),
    nick_name character varying(200),
    sex character varying(2),
    phone character varying(15),
    headimg_url character varying(1000),
    union_id character varying(64),
    session_key character varying(200),
    mall_user_id character varying(32)
);

COMMENT ON TABLE wx_user IS '微信用户';

COMMENT ON COLUMN wx_user.id IS '主键';

COMMENT ON COLUMN wx_user.create_id IS '创建者';

COMMENT ON COLUMN wx_user.create_time IS '创建时间';

COMMENT ON COLUMN wx_user.update_id IS '更新者';

COMMENT ON COLUMN wx_user.update_time IS '更新时间';

COMMENT ON COLUMN wx_user.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN wx_user.tenant_id IS '所属租户';

COMMENT ON COLUMN wx_user.app_id IS '公众号配置ID、小程序AppID';

COMMENT ON COLUMN wx_user.app_type IS '应用类型(1:小程序，2:公众号)';

COMMENT ON COLUMN wx_user.open_id IS '用户标识';

COMMENT ON COLUMN wx_user.nick_name IS '昵称';

COMMENT ON COLUMN wx_user.sex IS '性别（1：男，2：女，0：未知）';

COMMENT ON COLUMN wx_user.phone IS '手机号码';

COMMENT ON COLUMN wx_user.headimg_url IS '头像';

COMMENT ON COLUMN wx_user.union_id IS 'union_id';

COMMENT ON COLUMN wx_user.session_key IS '会话密钥';

COMMENT ON COLUMN wx_user.mall_user_id IS '商城用户ID';

CREATE TABLE wx_user_1 (
    id character varying(32) DEFAULT ''::character varying NOT NULL,
    create_id character varying(32),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_id character varying(32),
    update_time timestamp without time zone,
    del_flag character varying(2) DEFAULT '0'::bpchar,
    tenant_id character varying(32) NOT NULL,
    app_id character varying(32) NOT NULL,
    app_type character varying(2),
    open_id character varying(64),
    nick_name character varying(200),
    sex character varying(2),
    phone character varying(15),
    headimg_url character varying(1000),
    union_id character varying(64),
    session_key character varying(200),
    mall_user_id character varying(32)
);

COMMENT ON TABLE wx_user_1 IS '微信用户';

COMMENT ON COLUMN wx_user_1.id IS '主键';

COMMENT ON COLUMN wx_user_1.create_id IS '创建者';

COMMENT ON COLUMN wx_user_1.create_time IS '创建时间';

COMMENT ON COLUMN wx_user_1.update_id IS '更新者';

COMMENT ON COLUMN wx_user_1.update_time IS '更新时间';

COMMENT ON COLUMN wx_user_1.del_flag IS '逻辑删除标记（0：显示；1：隐藏）';

COMMENT ON COLUMN wx_user_1.tenant_id IS '所属租户';

COMMENT ON COLUMN wx_user_1.app_id IS '公众号配置ID、小程序AppID';

COMMENT ON COLUMN wx_user_1.app_type IS '应用类型(1:小程序，2:公众号)';

COMMENT ON COLUMN wx_user_1.open_id IS '用户标识';

COMMENT ON COLUMN wx_user_1.nick_name IS '昵称';

COMMENT ON COLUMN wx_user_1.sex IS '性别（1：男，2：女，0：未知）';

COMMENT ON COLUMN wx_user_1.phone IS '手机号码';

COMMENT ON COLUMN wx_user_1.headimg_url IS '头像';

COMMENT ON COLUMN wx_user_1.union_id IS 'union_id';

COMMENT ON COLUMN wx_user_1.session_key IS '会话密钥';

COMMENT ON COLUMN wx_user_1.mall_user_id IS '商城用户ID';

ALTER TABLE ONLY wx_app
    ADD CONSTRAINT wx_app_pkey PRIMARY KEY (id);

ALTER TABLE ONLY wx_user
    ADD CONSTRAINT wx_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY wx_user_1
    ADD CONSTRAINT wx_user_1_pkey PRIMARY KEY (id);

CREATE INDEX wx_app_ids_tenant_id ON wx_app USING btree (tenant_id);

CREATE INDEX wx_user_ids_app_id ON wx_user USING btree (app_id, tenant_id);

CREATE INDEX wx_user_ids_tenant_id ON wx_user USING btree (tenant_id);

CREATE UNIQUE INDEX wx_user_uk_appid_openid ON wx_user USING btree (app_id, open_id) WHERE ((del_flag)::text = '0'::text);

CREATE INDEX wx_user_1_ids_app_id ON wx_user_1 USING btree (app_id, tenant_id);

CREATE INDEX wx_user_1_ids_tenant_id ON wx_user_1 USING btree (tenant_id);

CREATE UNIQUE INDEX wx_user_1_uk_appid_openid ON wx_user_1 USING btree (app_id, open_id) WHERE ((del_flag)::text = '0'::text);

CREATE TRIGGER on_update_current_timestamp BEFORE UPDATE ON wx_app FOR EACH ROW EXECUTE FUNCTION on_update_current_timestamp();

CREATE TRIGGER on_update_current_timestamp BEFORE UPDATE ON wx_user FOR EACH ROW EXECUTE FUNCTION on_update_current_timestamp();

CREATE TRIGGER on_update_current_timestamp BEFORE UPDATE ON wx_user_1 FOR EACH ROW EXECUTE FUNCTION on_update_current_timestamp();
