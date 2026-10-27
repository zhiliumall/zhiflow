package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.GoodsParameterTemplateService;
import com.zhiflow.mall.common.entity.GoodsParameterTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsparametertemplate")
@Tag(description = "goodsparametertemplate", name = "参数模板")
public class GoodsParameterTemplateController {
    private final GoodsParameterTemplateService goodsParameterTemplateService;

    /**
     * 分页查询
     * @param page 分页对象
     * @param goodsParameterTemplate 规格
     * @return
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:get')")
    public R getGoodsSpecTemplatePage(Page page, GoodsParameterTemplate goodsParameterTemplate) {
        LambdaQueryWrapper<GoodsParameterTemplate> like = new LambdaQueryWrapper<GoodsParameterTemplate>()
                .like(StringUtils.isNotBlank(goodsParameterTemplate.getName()), GoodsParameterTemplate::getName, goodsParameterTemplate.getName())
                .orderByAsc(GoodsParameterTemplate::getSort);
        return R.ok(goodsParameterTemplateService.page(page, like));
    }

    @Operation(summary = "list查询")
    @GetMapping("/list")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:get')")
    public R getGoodsSpecTemplateList(GoodsParameterTemplate goodsParameterTemplate) {
        LambdaQueryWrapper<GoodsParameterTemplate> like = new LambdaQueryWrapper<GoodsParameterTemplate>()
                .like(StringUtils.isNotBlank(goodsParameterTemplate.getName()), GoodsParameterTemplate::getName, goodsParameterTemplate.getName())
                .orderByAsc(GoodsParameterTemplate::getSort);
        return R.ok(goodsParameterTemplateService.list(like));
    }

    /**
     * 通过id查询规格
     * @param id
     * @return R
     */
    @Operation(summary = "通过id查询规格")
    @GetMapping("/{id}")
    // [R6 批三·读 2026-08-17] 补方法级鉴权，复用本模块既有读码（无新增权限码）。
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:get')")
    public R getById(@PathVariable("id") String id){
        return R.ok(goodsParameterTemplateService.getById(id));
    }

    /**
     * 新增规格
     * @param goodsParameterTemplate 规格
     * @return R
     */
    @Operation(summary = "新增规格")
    @SysLog("新增规格")
    @PostMapping
    // [R6 批三 2026-08-17] 原先无任何 @PreAuthorize ⇒ 全部后台账号可调。与 GoodsParameterController 同一个「商品参数」页，共用码。
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:add')")
    public R save(@RequestBody GoodsParameterTemplate goodsParameterTemplate){
        try {
            goodsParameterTemplateService.save(goodsParameterTemplate);
            return R.ok(goodsParameterTemplate);
        }catch (DuplicateKeyException e){
            if(e.getMessage().contains("uk_name")){
                return R.failed("该规格已存在");
            }else{
                return R.failed("新增规格失败，请稍后重试");
            }
        }
    }

    /**
     * 修改规格
     * @param goodsParameterTemplate 规格
     * @return R
     */
    @Operation(summary = "修改规格")
    @SysLog("修改规格")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:edit')")
    public R updateById(@RequestBody GoodsParameterTemplate goodsParameterTemplate){
        try {
            return R.ok(goodsParameterTemplateService.updateById(goodsParameterTemplate));
        }catch (DuplicateKeyException e){
            if(e.getMessage().contains("uk_name")){
                return R.failed("该规格已存在");
            }else{
                return R.failed("修改规格失败，请稍后重试");
            }
        }
    }

    /**
     * 通过id删除参数
     * @param id
     * @return R
     */
    @Operation(summary = "通过id删除参数")
    @SysLog("删除规格")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodstmp:del')")
    public R removeById(@PathVariable String id){
        return R.ok(goodsParameterTemplateService.removeById(id));
    }

}
