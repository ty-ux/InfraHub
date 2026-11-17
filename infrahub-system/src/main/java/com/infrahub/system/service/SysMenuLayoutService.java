package com.infrahub.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.system.mapper.SysMenuLayoutMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SysMenuLayoutService {

    private final SysMenuLayoutMapper mapper;
    private final ObjectMapper objectMapper;

    public State get() {
        var stored = mapper.find();
        try {
            return new State(stored.revision(), objectMapper.readValue(stored.layout(), Layout.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored menu layout", exception);
        }
    }

    public Optional<State> save(State state) {
        var layout = state.layout();
        if (layout.groupOrder().stream().distinct().count() != layout.groupOrder().size()
                || layout.entries().stream().map(Entry::path).distinct().count() != layout.entries().size()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "菜单分组或页面重复");
        }
        try {
            String json = objectMapper.writeValueAsString(layout);
            if (mapper.update(state.revision(), json) == 0) {
                return Optional.empty();
            }
            return Optional.of(new State(state.revision() + 1, layout));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize menu layout", exception);
        }
    }

    public record State(@NotNull @PositiveOrZero Integer revision, @NotNull @Valid Layout layout) {
    }

    public record Layout(
            @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = "[a-z][a-z0-9]{0,31}") String> groupOrder,
            @NotNull @Size(max = 250) List<@NotNull @Valid Entry> entries) {
    }

    public record Entry(
            @NotNull @Size(max = 150) @Pattern(regexp = "/[a-z0-9]+(?:[-/][a-z0-9]+)*") String path,
            @NotNull @Pattern(regexp = "[a-z][a-z0-9]{0,31}") String group,
            @NotNull Boolean visible) {
    }
}
