package com.infrahub.system.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SysMenuLayoutMapper {

    @Select("SELECT revision, layout::text AS layout FROM sys_menu_layout WHERE id = 1")
    StoredLayout find();

    @Update("""
            UPDATE sys_menu_layout
            SET layout = CAST(#{layout} AS jsonb), revision = revision + 1
            WHERE id = 1 AND revision = #{revision}
            """)
    int update(@Param("revision") int revision, @Param("layout") String layout);

    record StoredLayout(int revision, String layout) {
    }
}
