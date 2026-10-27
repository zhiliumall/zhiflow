package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.GoodsParameterService;
import com.zhiflow.mall.admin.service.GoodsParameterValueService;
import com.zhiflow.mall.common.entity.GoodsParameter;
import com.zhiflow.mall.common.entity.GoodsParameterValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsparameter")
@Tag(description = "goodsparameter", name = "商品参数")
public class GoodsParameterController {
    private final GoodsParameterService goodsParameterService;
    private final GoodsParameterValueService goodsParameterValueService;

    @Operation(summary = "list查询")
    @GetMapping("/list")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:get')")
    public R getGoodsSpecList(GoodsParameter goodsParameter) {
        return R.ok(goodsParameterService.list1(goodsParameter));
    }

    /**
     * 新增参数
     */
    @Operation(summary = "新增参数")
    @SysLog("新增参数")
    @PostMapping
    // [R6 批三 2026-08-17] 原先无任何 @PreAuthorize ⇒ 全部后台账号可调。沿用「商品参数」页既有的 mall:goodstmp:* 家族（原只有 :get）。
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:add')")
    public R save(@RequestBody GoodsParameter goodsParameter) {
        try {
            goodsParameterService.save(goodsParameter);
            List<GoodsParameterValue> goodsSpecValueList = goodsParameter.getGoodsParameterValueList();
            if (goodsSpecValueList != null && !goodsSpecValueList.isEmpty()) {
                goodsSpecValueList.forEach(a -> a.setParameterId(goodsParameter.getId()));
                goodsParameterValueService.saveBatch(goodsSpecValueList);
            }
            return R.ok(goodsParameter);
        } catch (DuplicateKeyException e) {
            if (e.getMessage().contains("uk_name")) {
                return R.failed("该参数已存在");
            } else {
                return R.failed("新增参数失败，请稍后重试");
            }
        }
    }

    /**
     * 修改参数
     */
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "修改参数")
    @SysLog("修改参数")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:edit')")
    public R updateById(@RequestBody GoodsParameter goodsParameter) {
        try {
            goodsParameterService.updateById(goodsParameter);
            List<GoodsParameterValue> goodsSpecValueList = goodsParameter.getGoodsParameterValueList();
            if (goodsSpecValueList != null && !goodsSpecValueList.isEmpty()) {
                goodsSpecValueList.forEach(a -> a.setParameterId(goodsParameter.getId()));
                goodsParameterValueService.saveOrUpdateBatch(goodsSpecValueList);
                goodsParameterValueService.remove(new LambdaQueryWrapper<GoodsParameterValue>()
                        .eq(GoodsParameterValue::getParameterId, goodsParameter.getId())
                        .notIn(GoodsParameterValue::getId, goodsSpecValueList.stream()
                                .map(GoodsParameterValue::getId)
                                .toList()));
            }

            return R.ok();
        } catch (DuplicateKeyException e) {
            if (e.getMessage().contains("uk_name")) {
                return R.failed("该参数已存在");
            } else {
                return R.failed("修改参数失败，请稍后重试");
            }
        }
    }

    /**
     * 通过id删除参数
     *
     * @param id
     * @return R
     */
    @Operation(summary = "通过id删除参数")
    @SysLog("删除规格")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:del')")
    public R removeById(@PathVariable String id) {
        return R.ok(goodsParameterService.removeById(id));
    }
}
