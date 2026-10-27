package com.zhiflow.common.core.jackson;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import com.zhiflow.common.core.annotation.HashidsId;
import com.zhiflow.common.core.util.IdCodec;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class HashidsSerializer extends ValueSerializer<String> {

    private final IdCodec idCodec;

    public HashidsSerializer() {
        this.idCodec = null;
    }

    public HashidsSerializer(IdCodec idCodec) {
        this.idCodec = idCodec;
    }

    public void serialize(String value, JsonGenerator gen, SerializationContext serializers) {
        // 范围判定必须在这里（每次序列化），不能放到 createContextual：
        // contextual 解析结果会被 Jackson 按属性缓存，判一次就定终身，逐请求切换会失效。
        if (idCodec == null || !HashidsScope.isEnabled() || value == null || value.isEmpty()) {
            gen.writeString(value);
            return;
        }
        String encoded = idCodec.encode(value);
        gen.writeString(encoded != null ? encoded : value);
    }

    public ValueSerializer<?> createContextual(SerializationContext prov, BeanProperty property) {
        if (property != null && property.getAnnotation(HashidsId.class) != null) {
            IdCodec codec = IdCodecHolder.getInstance();
            return new HashidsSerializer(codec);
        }
        return this;
    }
}
