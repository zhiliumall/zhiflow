package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.admin.mapper.GoodsCategoryMapper;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.mapper.OrderInfoMapper;
import com.zhiflow.mall.admin.service.EntityImpactService;
import com.zhiflow.mall.common.dto.ImpactVO;
import com.zhiflow.mall.common.entity.GoodsCategory;
import com.zhiflow.mall.common.entity.GoodsSpu;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import static com.zhiflow.mall.common.dto.ImpactVO.item;

/**
 * 删除影响面计数实现。
 *
 * <p><b>三条自我约束，改这个类前先读：</b>
 * <ol>
 *   <li><b>只读</b>。只允许出现 {@code selectCount} / {@code selectById}，不得引入任何写调用。</li>
 *   <li><b>不写 try/catch</b>。异常一律抛给全局异常处理器——catch 后 return 失败响应会命中门禁 R5
 *       （吞异常未留痕），也会让前端把「查询失败」误读成「无影响」。</li>
 *   <li><b>一律带 {@code del_flag='0'}</b>。软删的行可能占大多数，
 *       不过滤会把影响面成倍夸大。口径与既有 Mapper XML 一致。</li>
 * </ol>
 */
@Service
@AllArgsConstructor
public class EntityImpactServiceImpl implements EntityImpactService {

	private final GoodsCategoryMapper goodsCategoryMapper;

	private final GoodsSpuMapper goodsSpuMapper;

	private final OrderInfoMapper orderInfoMapper;

	@Override
	public ImpactVO goodsCategoryImpact(String categoryId) {
		// 只数直接下级：分类最多三级，禁止删有直接子分类的节点即可保住整棵子树
		long children = count(goodsCategoryMapper.selectCount(Wrappers.<GoodsCategory>lambdaQuery()
				.eq(GoodsCategory::getParentId, categoryId)
				.eq(GoodsCategory::getDelFlag, CommonConstants.STATUS_NORMAL)));
		// 分类可挂在一/二/三级任一层，三列都要看
		long goods = count(goodsSpuMapper.selectCount(Wrappers.<GoodsSpu>lambdaQuery()
				.eq(GoodsSpu::getDelFlag, CommonConstants.STATUS_NORMAL)
				.and(w -> w.eq(GoodsSpu::getCategoryFirst, categoryId)
						.or().eq(GoodsSpu::getCategorySecond, categoryId)
						.or().eq(GoodsSpu::getCategoryThird, categoryId))));

		return ImpactVO.of(
				item("直接子分类", children),
				item("分类下商品", goods));
	}

	/** selectCount 在无匹配行时可能返回 null，统一兜 0 */
	private static long count(Long value) {
		return value == null ? 0L : value;
	}
}
