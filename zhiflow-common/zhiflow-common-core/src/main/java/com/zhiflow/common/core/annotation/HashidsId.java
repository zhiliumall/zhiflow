package com.zhiflow.common.core.annotation;

import com.zhiflow.common.core.jackson.HashidsDeserializer;
import com.zhiflow.common.core.jackson.HashidsSerializer;
import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = HashidsSerializer.class)
@JsonDeserialize(using = HashidsDeserializer.class)
public @interface HashidsId {
}
