package com.zhiflow.common.core.sensitive;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import com.zhiflow.common.core.util.SensitiveUtils;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.util.Objects;

/**
 * @Author: https://www.cnblogs.com/xiluonanfeng/p/10183926.html
 * 脱敏序列化
 */
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveSerialize extends ValueSerializer<String> {

	private SensitiveTypeEnum type;

	public void serialize(final String originStr, final JsonGenerator jsonGenerator,
                          final SerializationContext serializerProvider) {
		switch (type) {
		case CHINESE_NAME:
			jsonGenerator.writeString(SensitiveUtils.chineseName(originStr));
			break;
		case MOBILE_PHONE:
			jsonGenerator.writeString(SensitiveUtils.mobilePhone(originStr));
			break;
		case EMAIL:
			jsonGenerator.writeString(SensitiveUtils.email(originStr));
			break;
		case PASSWORD:
			jsonGenerator.writeString(SensitiveUtils.password(originStr));
			break;
		case KEY:
			jsonGenerator.writeString(SensitiveUtils.key(originStr));
			break;
		case ID_CARD:
			jsonGenerator.writeString(SensitiveUtils.idCard(originStr));
			break;
		default:
			throw new IllegalArgumentException("未定义的敏感信息枚举类" + type);
		}
	}

	public ValueSerializer<?> createContextual(final SerializationContext serializerProvider,final BeanProperty beanProperty) throws DatabindException {
		if (beanProperty != null) {
			if (Objects.equals(beanProperty.getType().getRawClass(), String.class)) {
				Sensitive sensitive = beanProperty.getAnnotation(Sensitive.class);
				if (sensitive == null) {
					sensitive = beanProperty.getContextAnnotation(Sensitive.class);
				}
				if (sensitive != null) {
					return new SensitiveSerialize(sensitive.type());
				}
			}
			// Jackson 3 把「按属性查找并上下文化的序列化器」改名为 findPrimaryPropertySerializer；
			// 同名的 findValueSerializer 在 Jackson 3 里只剩不带 BeanProperty 的重载，语义是"按类型查"，不等价。
			return serializerProvider.findPrimaryPropertySerializer(beanProperty.getType(), beanProperty);
		}
		return serializerProvider.findNullValueSerializer(null);
	}

}
