package com.zhixing.dao.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * JSONB 列的字符串 TypeHandler。坑：MyBatis 默认 setString 发送的是 VARCHAR 类型参数，
 * PG 拒绝 varchar → jsonb 隐式转换（实测报错 "column is of type jsonb but expression is
 * of type character varying"）；setObject(i, v, Types.OTHER) 让参数以未类型化形式发送，
 * 服务端按目标列 jsonb 解析——等价于 SQL 里写 ?::jsonb，但不需要改 MP 的 insert 语句。
 */
@MappedTypes(String.class)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
