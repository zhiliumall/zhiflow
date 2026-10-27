package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.IdCodec;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.GoodsSpuService;
import com.zhiflow.mall.api.vo.GoodsParameterItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商详页参数区（S-44）：参数在后台按参数模板录入，存在商品的 {@code parameter_json} 里，这里解析成「名称 / 值」列表。
 */
@ApiLogin(mustLogin = false)
@RestController
@RequiredArgsConstructor
@RequestMapping("/goodsparameter")
@Tag(name = "商品参数")
public class GoodsParameterController {

	private final GoodsSpuService goodsSpuService;

	private final IdCodec idCodec;

	@Operation(summary = "商品参数")
	@GetMapping("/spu/{spuId}")
	public R<List<GoodsParameterItem>> listBySpu(@PathVariable("spuId") String spuId) {
		// 商品 id 前端可能传 hashid 加密串，与商详、规格接口同样先解码
		String id = idCodec.isHashid(spuId) ? idCodec.decode(spuId) : spuId;
		return R.ok(goodsSpuService.listParameters(id));
	}
}
