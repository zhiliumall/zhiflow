package com.zhiflow.common.core.jackson;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import com.zhiflow.common.core.annotation.HashidsId;
import com.zhiflow.common.core.util.IdCodec;
import tools.jackson.databind.ValueDeserializer;

public class HashidsDeserializer extends ValueDeserializer<String> {

    private final IdCodec idCodec;

    public HashidsDeserializer() {
        this.idCodec = null;
    }

    public HashidsDeserializer(IdCodec idCodec) {
        this.idCodec = idCodec;
    }

    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        String value = p.getValueAsString();
        // 与 HashidsSerializer 同一条范围判据，且同样必须逐次判（contextual 结果会被缓存）。
        // 不对称的话会出现「B 端响应给原始 id、回传却被当 hashid 解码」这种更难查的形态。
        if (idCodec == null || !HashidsScope.isEnabled() || value == null || value.isEmpty()) {
            return value;
        }
        String decoded = idCodec.decode(value);
        return decoded != null ? decoded : value;
    }

    public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        if (property != null && property.getAnnotation(HashidsId.class) != null) {
            IdCodec codec = IdCodecHolder.getInstance();
            return new HashidsDeserializer(codec);
        }
        return this;
    }
}
