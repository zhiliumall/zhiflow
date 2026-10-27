package com.zhiflow.common.core.util;

import org.hashids.Hashids;

/**
 * 雪花 ID ↔ Hashid 可逆映射工具
 */
public class IdCodec {

    private static final int MIN_HASH_LENGTH = 10;
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final String ID_PATTERN = "^[a-z2-9]{10,}$";
    private static final String SNOWFLAKE_PATTERN = "^\\d{15,20}$";

    private final Hashids hashids;
    private final boolean noOp;

    public IdCodec() {
        this.hashids = null;
        this.noOp = true;
    }

    public IdCodec(String salt) {
        if (salt == null || salt.length() < 32) {
            throw new IllegalArgumentException("hashids.salt must be at least 32 characters");
        }
        this.hashids = new Hashids(salt, MIN_HASH_LENGTH, ALPHABET);
        this.noOp = false;
    }

    public String encode(String snowflakeId) {
        if (noOp || snowflakeId == null || snowflakeId.isEmpty()) return snowflakeId;
        try {
            return hashids.encodeHex(snowflakeId);
        } catch (Exception e) {
            return null;
        }
    }

    public String decode(String hashid) {
        if (noOp || hashid == null || !hashid.matches(ID_PATTERN)) return hashid;
        try {
            return hashids.decodeHex(hashid);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isHashid(String value) {
        return value != null && value.matches(ID_PATTERN);
    }

    public boolean isSnowflake(String value) {
        return value != null && value.matches(SNOWFLAKE_PATTERN);
    }
}
