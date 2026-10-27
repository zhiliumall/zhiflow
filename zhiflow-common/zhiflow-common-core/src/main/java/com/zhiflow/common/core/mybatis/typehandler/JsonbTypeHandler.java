package com.zhiflow.common.core.mybatis.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL {@code jsonb} 列的 TypeHandler（hutool {@link JSONObject} 字段）。
 * <p>
 * 与 {@link JsonTypeHandler} 的差异只在写入：本类走 {@code setObject(i, jsonText, Types.OTHER)}，
 * pgjdbc 以 UNSPECIFIED 绑定、由 PG 按目标列推断为 jsonb；而 {@code setString} 会被当作
 * varchar，在未配 {@code stringtype=unspecified} 的数据源上写真 jsonb 列报
 * "column ... is of type jsonb but expression is of type character varying"。
 * 读取沿用 JSON 文本解析，空值返回 {@code null}（不返回空对象）。
 */
@MappedTypes({JSONObject.class})
@MappedJdbcTypes({JdbcType.OTHER})
public class JsonbTypeHandler extends BaseTypeHandler<JSONObject> {

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i, JSONObject parameter,
									JdbcType jdbcType) throws SQLException {

		ps.setObject(i, JSONUtil.toJsonStr(parameter), Types.OTHER);
	}

	@Override
	public JSONObject getNullableResult(ResultSet rs, String columnName) throws SQLException {

		return parse(rs.getString(columnName));
	}

	@Override
	public JSONObject getNullableResult(ResultSet rs, int columnIndex) throws SQLException {

		return parse(rs.getString(columnIndex));
	}

	@Override
	public JSONObject getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {

		return parse(cs.getString(columnIndex));
	}

	private JSONObject parse(String raw) {
		if (StrUtil.isBlank(raw)) {
			return null;
		}
		return JSONUtil.parseObj(raw).toBean(JSONObject.class);
	}
}
