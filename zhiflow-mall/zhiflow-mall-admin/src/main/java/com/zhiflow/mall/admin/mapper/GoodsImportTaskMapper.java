package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.GoodsImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GoodsImportTaskMapper extends BaseMapper<GoodsImportTask> {

	/**
	 * 单取 rows_json。实体上这两列标了 {@code @TableField(select = false)}，
	 * 否则每次列表查询都要把几百行 JSON 拖回来。
	 *
	 * <p>注解 SQL 不走 {@code @TableLogic}，del_flag 谓词必须手写；
	 * 租户 schema 由 SchemaInterceptor 设的 search_path 负责，这里不能也不需要写死 schema。
	 */
	@Select("SELECT rows_json FROM goods_import_task WHERE id = #{id} AND del_flag = '0'")
	String selectRowsJson(@Param("id") String id);

	@Select("SELECT fail_json FROM goods_import_task WHERE id = #{id} AND del_flag = '0'")
	String selectFailJson(@Param("id") String id);
}
