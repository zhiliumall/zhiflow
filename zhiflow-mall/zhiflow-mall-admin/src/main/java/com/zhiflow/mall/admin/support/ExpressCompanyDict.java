package com.zhiflow.mall.admin.support;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.api.upms.SysDictApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.SysDictValueInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快递公司字典（S-23）：物流公司只认字典 {@value #DICT_TYPE} 里的编码，发货时按编码取名称。
 */
@Component
@RequiredArgsConstructor
public class ExpressCompanyDict {

	public static final String DICT_TYPE = "express_company";

	private final SysDictApi sysDictApi;

	/**
	 * 按编码取快递公司名称。
	 *
	 * @throws BizException 编码不在字典里
	 */
	public String requireName(String code) {
		String name = code == null ? null : load().get(code);
		if (name == null) {
			throw new BizException("物流公司「" + code + "」不在快递公司字典里");
		}
		return name;
	}

	/** 字典快照：编码 → 名称，按字典排序。批量处理时取一次反复用，不必每行查一遍。 */
	public Map<String, String> load() {
		R<List<SysDictValueInfo>> r = sysDictApi.getInsideDictByType(DICT_TYPE, SecurityConstants.FROM_IN);
		List<SysDictValueInfo> values = r == null || r.getData() == null ? List.of() : r.getData();
		Map<String, String> byCode = new LinkedHashMap<>();
		values.forEach(v -> byCode.putIfAbsent(v.getValue(), v.getLabel()));
		return byCode;
	}

	/**
	 * 填的是编码或名称都认（名称不区分首尾空格）。认不出返回 null。
	 *
	 * @param snapshot {@link #load()} 的结果
	 */
	public static String resolveCode(Map<String, String> snapshot, String codeOrName) {
		String input = StrUtil.trim(codeOrName);
		if (StrUtil.isBlank(input)) {
			return null;
		}
		if (snapshot.containsKey(input)) {
			return input;
		}
		return snapshot.entrySet().stream()
				.filter(e -> input.equals(StrUtil.trim(e.getValue())))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
	}
}
