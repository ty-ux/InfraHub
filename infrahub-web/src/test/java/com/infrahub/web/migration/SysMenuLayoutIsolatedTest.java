package com.infrahub.web.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.system.mapper.SysMenuLayoutMapper;
import com.infrahub.system.service.SysMenuLayoutService;
import com.infrahub.system.service.SysMenuLayoutService.Entry;
import com.infrahub.system.service.SysMenuLayoutService.Layout;
import com.infrahub.system.service.SysMenuLayoutService.State;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SysMenuLayoutIsolatedTest extends MigrationIsolationSupport {

    @Test
    void migrationSupportsSavingAndRejectsAnOutdatedRevision() {
        assumeAvailable();
        String database = createOwnedDatabase();
        execute(database, readClasspathText("db/migration/V36__create_system_menu_layout.sql"));
        var configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSourceFor(database)));
        configuration.addMapper(SysMenuLayoutMapper.class);

        var defaults = new Layout(List.of(), List.of());
        var custom = new Layout(List.of("delivery", "workspace"),
                List.of(new Entry("/release/versions", "delivery", false)));
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var service = new SysMenuLayoutService(session.getMapper(SysMenuLayoutMapper.class), new ObjectMapper());
            assertThat(service.get()).isEqualTo(new State(0, defaults));
            assertThat(service.save(new State(0, custom))).contains(new State(1, custom));
            assertThat(service.save(new State(0, defaults))).isEmpty();
            assertThat(service.get()).isEqualTo(new State(1, custom));
            assertThat(service.save(new State(1, defaults))).contains(new State(2, defaults));
            assertThat(service.get()).isEqualTo(new State(2, defaults));
        }
    }
}
