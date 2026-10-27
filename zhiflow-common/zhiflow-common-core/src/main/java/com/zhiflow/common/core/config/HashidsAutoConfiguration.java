package com.zhiflow.common.core.config;

import com.zhiflow.common.core.jackson.IdCodecHolder;
import com.zhiflow.common.core.util.IdCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HashidsAutoConfiguration {

    @Bean
    public IdCodec idCodec(@Value("${hashids.salt:}") String salt) {
        IdCodec codec = (salt != null && !salt.isEmpty())
                ? new IdCodec(salt)
                : new IdCodec();
        IdCodecHolder.setInstance(codec);
        return codec;
    }
}
