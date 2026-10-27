package com.zhiflow.common.core.mybatis.typehandler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.util.Arrays;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *  存储到数据库, 将String数组转换成字符串;
 *  从数据库获取数据, 将字符串转为LONG数组.
 */
@MappedTypes({String[].class})
@MappedJdbcTypes({JdbcType.VARCHAR})
public class ArrayStringTypeHandler extends BaseTypeHandler<String[]> {

	private static String[] l = new String[]{};

	@Override
	public void setNonNullParameter(PreparedStatement ps, int i,
									String[] parameter, JdbcType jdbcType) throws SQLException {
		ps.setString(i, JSONUtil.toJsonStr(parameter));
	}

	@Override
	public String[] getNullableResult(ResultSet rs, String columnName)
			throws SQLException {
		return parseValue(rs.getString(columnName));
	}

	@Override
	public String[] getNullableResult(ResultSet rs, int columnIndex)
			throws SQLException {
		return parseValue(rs.getString(columnIndex));
	}

	@Override
	public String[] getNullableResult(CallableStatement cs, int columnIndex)
			throws SQLException {
		return parseValue(cs.getString(columnIndex));
	}

	private String[] parseValue(String value) {
		if (StrUtil.isBlank(value)) {
			return l;
		}

		String text = value.trim();
		if (text.startsWith("[")) {
			return JSONUtil.parseArray(text).toArray(l);
		}

		if (text.contains(",")) {
			return Arrays.stream(text.split(","))
				.map(String::trim)
				.filter(StrUtil::isNotBlank)
				.toArray(String[]::new);
		}

		return new String[]{text};
	}

}
