package com.infrahub.release.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.CreateAssetHostRequest;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.service.AssetHostProfileService;
import com.infrahub.release.service.AssetHostService;
import com.infrahub.release.service.ResourceCapacityRiskService;
import com.infrahub.release.service.ResourceGovernanceActionPlanService;
import com.infrahub.release.service.ResourceGovernanceSuggestionService;
import com.infrahub.release.service.ResourcePoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主机登记接口在真实 MVC 校验入口上的契约测试。
 *
 * <p>{@code AssetHostServiceTest} 是直连 service 的单元测试，证明不了 {@code @Valid} 之后的行为：
 * 注解在 DTO 上、在 MVC 参数解析阶段生效，只有把请求真的发进 DispatcherServlet 才看得到。
 * 这里用 {@code standaloneSetup} 起最小 MVC 栈（不连数据库、不连 SSH、不发任何真实请求出去），
 * 把 JSON 送进控制器，断言 HTTP 状态码与控制器实际交给 service 的 DTO。
 *
 * <p>覆盖两条容易分裂的契约：
 * <ul>
 *   <li>文本字段首尾空白：接口接受并裁剪，而不是报格式错误；</li>
 *   <li>IP 与规格取值范围：非法值在 MVC 层就以 400 拒绝，不会走到 service，更不会变成 500 或静默"未填写"。</li>
 * </ul>
 */
class AssetHostCreateHttpContractTest {

    private static final String HOSTS_PATH = "/api/assets/hosts";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AssetHostService assetHostService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assetHostService = mock(AssetHostService.class);
        AssetController controller = new AssetController(
                mock(ResourcePoolService.class),
                assetHostService,
                mock(AssetHostProfileService.class),
                mock(ResourceCapacityRiskService.class),
                mock(ResourceGovernanceSuggestionService.class),
                mock(ResourceGovernanceActionPlanService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(assetHostService.createHost(any(CreateAssetHostRequest.class))).thenAnswer(invocation -> {
            AssetHost host = new AssetHost();
            host.setHostname(invocation.<CreateAssetHostRequest>getArgument(0).getHostname());
            host.setHostStatus(AssetHostService.INITIAL_STATUS_UNKNOWN);
            return host;
        });
    }

    /** 一份合法的请求体，测试按需覆盖其中字段 */
    private Map<String, Object> payload() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hostname", "app-01");
        body.put("ip", "192.0.2.3");
        body.put("envId", 2);
        body.put("osType", "LINUX");
        body.put("hostType", "VM");
        return body;
    }

    private ResultActions postHost(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(HOSTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> payloadWith(String key, Object value) {
        Map<String, Object> body = payload();
        body.put(key, value);
        return body;
    }

    private CreateAssetHostRequest captureRequest() {
        ArgumentCaptor<CreateAssetHostRequest> captor = ArgumentCaptor.forClass(CreateAssetHostRequest.class);
        verify(assetHostService).createHost(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("空白契约：首尾带空格的主机名/IP/类型码经 HTTP 进来被裁剪后放行，而不是被格式规则拒绝")
    void trimsSurroundingWhitespaceInsteadOfRejectingIt() throws Exception {
        Map<String, Object> body = payload();
        body.put("hostname", "  app-01  ");
        body.put("ip", "  192.0.2.3  ");
        body.put("osType", "  LINUX  ");
        body.put("hostType", "  VM  ");

        postHost(body).andExpect(status().isOk());

        CreateAssetHostRequest received = captureRequest();
        assertEquals("app-01", received.getHostname());
        assertEquals("192.0.2.3", received.getIp());
        assertEquals("LINUX", received.getOsType());
        assertEquals("VM", received.getHostType());
    }

    @Test
    @DisplayName("纯空白等于未填写：主机名只有空格时以校验错误拒绝（400），不是 500")
    void rejectsWhitespaceOnlyRequiredFieldAsValidationError() throws Exception {
        postHost(payloadWith("hostname", "   ")).andExpect(status().isBadRequest());

        verify(assetHostService, never()).createHost(any(CreateAssetHostRequest.class));
    }

    @Test
    @DisplayName("IPv6 边界：首尾单冒号与非法压缩语法在 MVC 层就被拒绝，进不了 service")
    void rejectsIllegalIpv6AtTheValidationLayer() throws Exception {
        List<String> illegal = List.of(
                // 本次修正的四类漏洞样本
                ":1:2:3:4:5:6:7:8",
                "1:2:3:4:5:6:7:8:",
                ":1::2",
                "1::2:",
                // 其它非法：重复压缩、超过 8 组、压缩后仍凑满 8 组、组内非十六进制、超长组
                "::1::2",
                "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7:8::",
                "2001:db8::zz",
                "12345::1",
                "fe80::1%eth0",
                ":::");

        for (String candidate : illegal) {
            ResultActions result = postHost(payloadWith("ip", candidate));
            result.andExpect(status().isBadRequest());
        }

        verify(assetHostService, never()).createHost(any(CreateAssetHostRequest.class));
    }

    @Test
    @DisplayName("IPv6 合法写法：压缩、全压缩、前导压缩、大小写十六进制与完整八组都放行")
    void acceptsLegalIpv6Literals() throws Exception {
        Stream.of("::", "::1", "1::", "2001:db8::1", "fe80::1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::",
                        // 大写/混合大小写的 ASCII 十六进制同样合法
                        "2001:DB8::1", "FE80::1", "ABCD:EF01::1", "2001:dB8::1")
                .forEach(candidate -> {
                    try {
                        postHost(payloadWith("ip", candidate)).andExpect(status().isOk());
                    } catch (Exception e) {
                        throw new AssertionError("合法 IPv6 被拒绝: " + candidate, e);
                    }
                });
    }

    @Test
    @DisplayName("非 ASCII 数字/字母不得被当作 IP：Unicode 变体在 MVC 层就以 400 拒绝，进不了 service")
    void rejectsNonAsciiIpCharactersAtTheValidationLayer() throws Exception {
        List<String> illegal = List.of(
                // 阿拉伯-印度数字 ١٢٧.٠.٠.١
                "١٢٧.٠.٠.١",
                // 全角数字 １２７.０.０.１
                "１２７.０.０.１",
                // 全角拉丁字母 Ａ::1
                "Ａ::1",
                // 半角/全角混排：ASCII 前缀 + 全角末位 127.0.0.１
                "127.0.0.１",
                // IPv6 组内全角十六进制 2001:ｄｂ８::1
                "2001:ｄｂ８::1",
                // IPv6 全角数字 ２００１::１
                "２００１::１");

        for (String candidate : illegal) {
            postHost(payloadWith("ip", candidate)).andExpect(status().isBadRequest());
        }

        verify(assetHostService, never()).createHost(any(CreateAssetHostRequest.class));
    }

    @Test
    @DisplayName("规格契约：显式 0、负数、越界、超过 2 位小数都在 MVC 层被拒绝（400），不靠数据库报错")
    void rejectsSpecsOutsideTheAgreedContract() throws Exception {
        List<Map<String, Object>> illegal = List.of(
                payloadWith("cpuCores", 0),
                payloadWith("cpuCores", -1),
                payloadWith("cpuCores", 1000000),
                payloadWith("cpuCores", new BigDecimal("0.001")),
                payloadWith("cpuCores", new BigDecimal("2.500")),
                payloadWith("memoryGb", 100000000),
                payloadWith("memoryGb", 0),
                payloadWith("diskGb", new BigDecimal("1.234")));

        for (Map<String, Object> body : illegal) {
            postHost(body).andExpect(status().isBadRequest());
        }

        verify(assetHostService, never()).createHost(any(CreateAssetHostRequest.class));
    }

    @Test
    @DisplayName("规格边界值：上限与两位小数被接受")
    void acceptsSpecBoundaryValues() throws Exception {
        Map<String, Object> body = payload();
        body.put("cpuCores", new BigDecimal("999999.99"));
        body.put("memoryGb", new BigDecimal("99999999.99"));
        body.put("diskGb", new BigDecimal("0.01"));

        postHost(body).andExpect(status().isOk());

        CreateAssetHostRequest received = captureRequest();
        assertEquals(new BigDecimal("999999.99"), received.getCpuCores());
        assertEquals(new BigDecimal("99999999.99"), received.getMemoryGb());
        assertEquals(new BigDecimal("0.01"), received.getDiskGb());
    }

    @Test
    @DisplayName("规格留空等于未填写：不传规格字段时按 null 交给 service，而不是 0")
    void keepsUnfilledSpecsAsNull() throws Exception {
        postHost(payload()).andExpect(status().isOk());

        CreateAssetHostRequest received = captureRequest();
        assertNull(received.getCpuCores());
        assertNull(received.getMemoryGb());
        assertNull(received.getDiskGb());
        assertNull(received.getResourcePoolId());
    }
}