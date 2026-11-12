package com.infrahub.base.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.entity.ConfigGroup;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.base.mapper.ConfigEnvConfigMapper;
import com.infrahub.base.mapper.ConfigGroupMapper;
import com.infrahub.base.mapper.ConfigSecretRefMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EnvConfigDocumentService {

    private static final String SECRET_REF = "$secretRef";
    private static final String LITERAL_JSON = "$json";

    private final ConfigEnvConfigMapper configMapper;
    private final ConfigGroupMapper groupMapper;
    private final BaseEnvMapper envMapper;
    private final ConfigSecretRefMapper secretRefMapper;
    private final ObjectMapper json;

    public record Document(String json, String revision) {}

    public record UngroupedScope(Long envId, Long appId) {}

    public List<UngroupedScope> listUngroupedScopes() {
        return configMapper.selectList(Wrappers.<ConfigEnvConfig>lambdaQuery()
                        .select(ConfigEnvConfig::getEnvId, ConfigEnvConfig::getAppId)
                        .isNull(ConfigEnvConfig::getGroupId)
                        .eq(ConfigEnvConfig::getIsDeleted, false)
                        .groupBy(ConfigEnvConfig::getEnvId, ConfigEnvConfig::getAppId))
                .stream().map(row -> new UngroupedScope(row.getEnvId(), row.getAppId())).toList();
    }

    public Document read(Long groupId, Long envId, Long appId) {
        return toDocument(readRows(resolveScope(groupId, envId, appId, false), false));
    }

    @Transactional
    public Document save(Long groupId, Long envId, Long appId, Document body) {
        JsonNode input = parseDocument(body.json());
        Scope scope = resolveScope(groupId, envId, appId, true);
        Map<String, ConfigEnvConfig> existing = readRows(scope, true);
        if (!revision(existing).equals(body.revision())) {
            throw invalid("配置已被更新，请刷新后重新编辑");
        }

        for (var fields = input.fields(); fields.hasNext();) {
            var field = fields.next();
            String key = field.getKey();
            if (key.isBlank() || key.length() > 128 || key.indexOf('\0') >= 0 || !key.equals(key.trim())) {
                throw invalid("配置键须为 1 到 128 个字符，首尾不能有空白");
            }
            ConfigEnvConfig current = existing.get(key);
            if (current == null || !field.getValue().equals(editorValue(current))) {
                write(scope, key, field.getValue(), current);
            }
        }
        existing.forEach((key, row) -> {
            if (!input.has(key)) {
                configMapper.update(new ConfigEnvConfig(), Wrappers.<ConfigEnvConfig>lambdaUpdate()
                        .eq(ConfigEnvConfig::getId, row.getId())
                        .set(ConfigEnvConfig::getIsDeleted, true));
            }
        });
        return toDocument(readRows(scope, false));
    }

    private Scope resolveScope(Long groupId, Long envId, Long appId, boolean lock) {
        if (groupId != null) {
            ConfigGroup group = lock ? groupMapper.lockById(groupId) : groupMapper.selectById(groupId);
            if (group == null) {
                throw invalid("配置分组不存在");
            }
            return new Scope(groupId, group.getEnvId(), group.getAppId());
        }
        if (envId == null || (lock && envMapper.lockById(envId) == null)) {
            throw invalid("请选择配置环境");
        }
        return new Scope(null, envId, appId);
    }

    private Map<String, ConfigEnvConfig> readRows(Scope scope, boolean lock) {
        LambdaQueryWrapper<ConfigEnvConfig> query = Wrappers.<ConfigEnvConfig>lambdaQuery()
                .eq(ConfigEnvConfig::getIsDeleted, false)
                .orderByAsc(ConfigEnvConfig::getConfigKey, ConfigEnvConfig::getId);
        if (scope.groupId() != null) {
            query.eq(ConfigEnvConfig::getGroupId, scope.groupId());
        } else {
            query.isNull(ConfigEnvConfig::getGroupId).eq(ConfigEnvConfig::getEnvId, scope.envId());
            if (scope.appId() == null) {
                query.isNull(ConfigEnvConfig::getAppId);
            } else {
                query.eq(ConfigEnvConfig::getAppId, scope.appId());
            }
        }
        if (lock) {
            query.last("FOR UPDATE");
        }
        Map<String, ConfigEnvConfig> rows = new LinkedHashMap<>();
        for (ConfigEnvConfig row : configMapper.selectList(query)) {
            if (rows.putIfAbsent(row.getConfigKey(), row) != null) {
                throw invalid("配置组中存在重复配置键：" + row.getConfigKey());
            }
        }
        return rows;
    }

    private Document toDocument(Map<String, ConfigEnvConfig> rows) {
        ObjectNode data = json.createObjectNode();
        rows.forEach((key, row) -> data.set(key, editorValue(row)));
        try {
            return new Document(json.writerWithDefaultPrettyPrinter().writeValueAsString(data), revision(rows));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法生成配置 JSON", e);
        }
    }

    private JsonNode editorValue(ConfigEnvConfig row) {
        if ("SECRET_REF".equalsIgnoreCase(row.getValueType())) {
            return json.createObjectNode().put(SECRET_REF, row.getSecretRefId());
        }
        String value = row.getConfigValue();
        if (value == null) {
            return json.nullNode();
        }
        String type = row.getValueType();
        boolean numeric = "NUMBER".equalsIgnoreCase(type);
        boolean booleanValue = "BOOLEAN".equalsIgnoreCase(type);
        boolean structured = "JSON".equalsIgnoreCase(type);
        if (!numeric && !booleanValue && !structured) {
            return json.getNodeFactory().textNode(value);
        }
        try {
            JsonNode parsed = parse(value);
            if ((numeric && parsed.isNumber()) || (booleanValue && parsed.isBoolean())) {
                return parsed;
            }
            if (structured && !parsed.isMissingNode()) {
                return parsed.isValueNode() || reserved(parsed, SECRET_REF) || reserved(parsed, LITERAL_JSON)
                        ? json.createObjectNode().set(LITERAL_JSON, parsed) : parsed;
            }
        } catch (JsonProcessingException ignored) {
            // Legacy values with invalid type metadata still round-trip as text.
        }
        return json.getNodeFactory().textNode(value);
    }

    private void write(Scope scope, String key, JsonNode value, ConfigEnvConfig current) {
        String type;
        String storedValue = null;
        Long secretRefId = null;
        if (reserved(value, SECRET_REF)) {
            JsonNode id = value.get(SECRET_REF);
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
                    || secretRefMapper.selectById(id.asLong()) == null) {
                throw invalid("配置键 " + key + " 的密钥引用不存在");
            }
            type = "SECRET_REF";
            secretRefId = id.asLong();
        } else if (value.isTextual() || value.isNull()) {
            type = current != null && "TEXT".equalsIgnoreCase(current.getValueType()) ? current.getValueType() : "STRING";
            storedValue = value.textValue();
        } else if (value.isNumber() || value.isBoolean()) {
            type = value.isNumber() ? "NUMBER" : "BOOLEAN";
            storedValue = value.toString();
        } else {
            type = "JSON";
            storedValue = (reserved(value, LITERAL_JSON) ? value.get(LITERAL_JSON) : value).toString();
        }
        try {
            if (current == null) {
                ConfigEnvConfig created = new ConfigEnvConfig();
                created.setGroupId(scope.groupId());
                created.setEnvId(scope.envId());
                created.setAppId(scope.appId());
                created.setConfigKey(key);
                created.setConfigValue(storedValue);
                created.setValueType(type);
                created.setSecretRefId(secretRefId);
                created.setVersionNo(1);
                configMapper.insert(created);
            } else {
                configMapper.update(new ConfigEnvConfig(), Wrappers.<ConfigEnvConfig>lambdaUpdate()
                        .eq(ConfigEnvConfig::getId, current.getId())
                        .set(ConfigEnvConfig::getConfigValue, storedValue)
                        .set(ConfigEnvConfig::getValueType, type)
                        .set(ConfigEnvConfig::getSecretRefId, secretRefId)
                        .set(ConfigEnvConfig::getVersionNo, current.getVersionNo() + 1));
            }
        } catch (DuplicateKeyException e) {
            throw invalid("当前应用和环境已存在配置键 " + key);
        }
    }

    private static boolean reserved(JsonNode value, String key) {
        return value.isObject() && value.size() == 1 && value.has(key);
    }

    private JsonNode parseDocument(String content) {
        if (content != null) {
            try {
                JsonNode parsed = parse(content);
                if (parsed.isObject()) {
                    return parsed;
                }
            } catch (JsonProcessingException e) {
                String line = e.getLocation() == null ? "" : "，请检查第 " + e.getLocation().getLineNr() + " 行";
                throw invalid("JSON 格式无效或包含重复键" + line);
            }
        }
        throw invalid("配置必须是 JSON 对象");
    }

    private JsonNode parse(String content) throws JsonProcessingException {
        return json.reader()
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(content);
    }

    private static String revision(Map<String, ConfigEnvConfig> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ConfigEnvConfig row : rows.values()) {
                digest.update((row.getId() + ":" + row.getVersionNo() + ":" + row.getUpdatedAt() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.VALIDATION_ERROR, message);
    }

    private record Scope(Long groupId, Long envId, Long appId) {}
}
