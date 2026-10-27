-- 智流商城数据库基线（public）。
-- 首次启动时由迁移器在 search_path = 本 schema 下执行一次；之后的结构变更写 V2、V3……。
-- 结构取自裁剪后保留的表与实体实际映射的列；种子不含任何真实用户、订单与口令哈希。

CREATE TABLE IF NOT EXISTS public.id_worker_node (
    slot_id       integer     NOT NULL,
    -- 占用者标识；NULL = 空闲。同一节点重启时凭它优先拿回原槽位，避免每次重启都换号段
    node_identity varchar(200),
    app_name      varchar(100),
    host          varchar(100),
    pid           varchar(50),
    -- 用 timestamptz + now()：写入与过期比较都在 DB 侧同源计算，
    -- 不受「应用 +8 / 直连 SQL 是 UTC」这个已知时区断层影响
    acquired_at   timestamptz,
    heartbeat_at  timestamptz,
    CONSTRAINT id_worker_node_pkey PRIMARY KEY (slot_id)
);

COMMENT ON TABLE public.id_worker_node IS '雪花机器位槽位注册表（全局表，非逐租户）。slot_id 0..1023 ↔ dataCenterId=slot/32, workerId=slot%32';

INSERT INTO public.id_worker_node (slot_id)
SELECT generate_series(0, 1023)
ON CONFLICT (slot_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS id_worker_node_heartbeat_idx
    ON public.id_worker_node (heartbeat_at);

CREATE UNIQUE INDEX IF NOT EXISTS id_worker_node_identity_idx
    ON public.id_worker_node (node_identity) WHERE node_identity IS NOT NULL;

CREATE TABLE state_guard_mode (
    id integer DEFAULT 1 NOT NULL,
    mode character varying(8) DEFAULT 'warn'::character varying NOT NULL,
    CONSTRAINT state_guard_mode_id_check CHECK ((id = 1)),
    CONSTRAINT state_guard_mode_mode_check CHECK (((mode)::text = ANY ((ARRAY['warn'::character varying, 'enforce'::character varying])::text[])))
);



CREATE TABLE state_guard_violation (
    id bigint NOT NULL,
    schema_name character varying(64) NOT NULL,
    table_name character varying(64) NOT NULL,
    row_id character varying(64),
    old_status character varying(24),
    new_status character varying(24),
    old_is_pay character varying(2),
    new_is_pay character varying(2),
    query_text text,
    client_addr character varying(64),
    app_name character varying(128),
    create_time timestamp with time zone DEFAULT now() NOT NULL
);



CREATE SEQUENCE state_guard_violation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;



ALTER SEQUENCE state_guard_violation_id_seq OWNED BY state_guard_violation.id;



ALTER TABLE ONLY state_guard_violation ALTER COLUMN id SET DEFAULT nextval('state_guard_violation_id_seq'::regclass);



ALTER TABLE ONLY state_guard_mode
    ADD CONSTRAINT state_guard_mode_pkey PRIMARY KEY (id);



ALTER TABLE ONLY state_guard_violation
    ADD CONSTRAINT state_guard_violation_pkey PRIMARY KEY (id);



CREATE INDEX idx_state_guard_violation_time ON state_guard_violation USING btree (create_time);

CREATE FUNCTION fn_state_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_mode text;
    v_old_pay text;
    v_new_pay text;
BEGIN
    IF TG_TABLE_NAME = 'order_info' THEN
        v_old_pay := OLD.is_pay;
        v_new_pay := NEW.is_pay;
        IF NEW.status IS NOT DISTINCT FROM OLD.status AND NEW.is_pay IS NOT DISTINCT FROM OLD.is_pay THEN
            RETURN NEW;
        END IF;
    ELSE
        IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
            RETURN NEW;
        END IF;
    END IF;
    IF coalesce(current_setting('app.state_machine', true), '') = '1' THEN
        RETURN NEW;
    END IF;
    SELECT mode INTO v_mode FROM public.state_guard_mode WHERE id = 1;
    INSERT INTO public.state_guard_violation (schema_name, table_name, row_id, old_status, new_status,
                                              old_is_pay, new_is_pay, query_text, client_addr, app_name)
    VALUES (TG_TABLE_SCHEMA, TG_TABLE_NAME, OLD.id::text, OLD.status::text, NEW.status::text,
            v_old_pay, v_new_pay, left(current_query(), 2000), inet_client_addr()::text,
            current_setting('application_name', true));
    IF v_mode = 'enforce' THEN
        RAISE EXCEPTION 'state guard: %.% id=% status % -> % changed outside the state machine',
            TG_TABLE_SCHEMA, TG_TABLE_NAME, OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RAISE WARNING 'state guard (warn): %.% id=% status % -> % changed outside the state machine',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, OLD.id, OLD.status, NEW.status;
    RETURN NEW;
END
$function$
;

INSERT INTO state_guard_mode (id, mode) VALUES (1, 'warn');
