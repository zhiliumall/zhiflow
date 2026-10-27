package com.zhiflow.common.core.jackson;

import com.zhiflow.common.core.util.IdCodec;

public class IdCodecHolder {

    private static volatile IdCodec instance;

    public static void setInstance(IdCodec codec) {
        instance = codec;
    }

    public static IdCodec getInstance() {
        if (instance == null) {
            throw new IllegalStateException("IdCodec not initialized. Ensure HashidsAutoConfiguration is loaded.");
        }
        return instance;
    }
}
