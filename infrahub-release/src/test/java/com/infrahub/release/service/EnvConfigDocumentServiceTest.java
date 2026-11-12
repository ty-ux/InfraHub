package com.infrahub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.entity.ConfigGroup;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.base.mapper.ConfigEnvConfigMapper;
import com.infrahub.base.mapper.ConfigGroupMapper;
import com.infrahub.base.mapper.ConfigSecretRefMapper;
import com.infrahub.base.service.EnvConfigDocumentService;
import com.infrahub.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EnvConfigDocumentServiceTest {

    private final ConfigEnvConfigMapper configs = mock(ConfigEnvConfigMapper.class);
    private final ConfigGroupMapper groups = mock(ConfigGroupMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final EnvConfigDocumentService service = new EnvConfigDocumentService(configs, groups,
            mock(BaseEnvMapper.class), mock(ConfigSecretRefMapper.class), json);

    @Test
    void documentPreservesTextNumbersAndSecretReferences() throws Exception {
        giveGroup();
        ConfigEnvConfig secret = row(1L, "db.secret", "hidden-legacy-value", "SECRET_REF");
        secret.setSecretRefId(4L);
        when(configs.selectList(any())).thenReturn(List.of(secret,
                row(2L, "script", "line 1\r\nline 2", "TEXT"),
                row(3L, "id", "9007199254740993", "NUMBER")));

        String document = service.read(11L, null, null).json();
        var data = json.readTree(document);

        assertThat(data.path("db.secret").path("$secretRef").asLong()).isEqualTo(4L);
        assertThat(document).doesNotContain("hidden-legacy-value");
        assertThat(data.path("script").asText()).isEqualTo("line 1\r\nline 2");
        assertThat(data.path("id").asLong()).isEqualTo(9007199254740993L);
    }

    @Test
    void savesNewKeysInTheSelectedGroupAndRejectsAStaleRevision() {
        giveGroup();
        when(configs.selectList(any())).thenReturn(List.of());
        String revision = service.read(11L, null, null).revision();

        assertThatThrownBy(() -> service.save(11L, null, null,
                new EnvConfigDocumentService.Document("{\"port\":8080}", "stale")))
                .isInstanceOf(BizException.class);
        verify(configs, never()).insert(any(ConfigEnvConfig.class));

        service.save(11L, 99L, 99L, new EnvConfigDocumentService.Document("{\"port\":8080}", revision));

        ArgumentCaptor<ConfigEnvConfig> created = ArgumentCaptor.forClass(ConfigEnvConfig.class);
        verify(configs).insert(created.capture());
        assertThat(created.getValue()).extracting(ConfigEnvConfig::getGroupId, ConfigEnvConfig::getAppId,
                ConfigEnvConfig::getEnvId, ConfigEnvConfig::getValueType, ConfigEnvConfig::getConfigValue)
                .containsExactly(11L, 12L, 13L, "NUMBER", "8080");
    }

    @Test
    void rejectsDuplicateKeysAndTrailingTokensBeforeReadingTheDatabase() {
        assertThatThrownBy(() -> service.save(11L, null, null,
                new EnvConfigDocumentService.Document("{\"port\":8080,\"port\":8081}", "")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(11L, null, null,
                new EnvConfigDocumentService.Document("{\"port\":8080} {}", "")))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(configs, groups);
    }

    private void giveGroup() {
        ConfigGroup group = new ConfigGroup();
        group.setId(11L);
        group.setAppId(12L);
        group.setEnvId(13L);
        when(groups.selectById(11L)).thenReturn(group);
        when(groups.lockById(11L)).thenReturn(group);
    }

    private static ConfigEnvConfig row(Long id, String key, String value, String type) {
        ConfigEnvConfig row = new ConfigEnvConfig();
        row.setId(id);
        row.setConfigKey(key);
        row.setConfigValue(value);
        row.setValueType(type);
        row.setVersionNo(1);
        return row;
    }
}
