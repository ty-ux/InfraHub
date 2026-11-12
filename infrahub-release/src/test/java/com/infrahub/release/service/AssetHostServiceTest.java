package com.infrahub.release.service;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateAssetHostRequest;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.mapper.AssetHostMapper;
import com.infrahub.release.support.IpLiterals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 主机手动登记的纯单元测试：不连真实 DB、不连 SSH、不接触任何真实主机。
 *
 * <p>覆盖范围：权限之外的全部后端规则（权限本身由 {@link
 * com.infrahub.release.controller.AssetHostCreateAuthorizationTest} 单独验证）、
 * 以及"登记不等于探测"的结构性保证。
 */
class AssetHostServiceTest {

    private static final Long ENV_ID = 2L;
    private static final Long POOL_ID = 5L;

    private AssetHostMapper mapper;
    private BaseEnvService baseEnvService;
    private ResourcePoolService resourcePoolService;
    private AssetHostService service;
    private final List<AssetHost> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mapper = mock(AssetHostMapper.class);
        baseEnvService = mock(BaseEnvService.class);
        resourcePoolService = mock(ResourcePoolService.class);
        service = new AssetHostService(baseEnvService, resourcePoolService);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        inserted.clear();
        when(mapper.insert(any(AssetHost.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });
        when(mapper.selectCount(any())).thenReturn(0L);

        BaseEnv env = new BaseEnv();
        env.setId(ENV_ID);
        env.setEnvName("测试环境");
        when(baseEnvService.getById(ENV_ID)).thenReturn(env);

        ResourcePool pool = new ResourcePool();
        pool.setId(POOL_ID);
        pool.setPoolName("默认池");
        when(resourcePoolService.getById(POOL_ID)).thenReturn(pool);
    }

    private CreateAssetHostRequest validRequest() {
        CreateAssetHostRequest request = new CreateAssetHostRequest();
        request.setHostname("app-01");
        request.setIp("192.0.2.3");
        request.setEnvId(ENV_ID);
        request.setResourcePoolId(POOL_ID);
        request.setOsType("LINUX");
        request.setHostType("VM");
        return request;
    }

    /** 依次给出查重结果：第 1 次是主机名查重，第 2 次是 IP 查重 */
    private void stubDuplicateResults(Long first, Long second) {
        when(mapper.selectCount(any())).thenReturn(first, second);
    }

    private CreateAssetHostRequest withCpu(String cpuCores) {
        CreateAssetHostRequest request = validRequest();
        request.setCpuCores(new BigDecimal(cpuCores));
        return request;
    }

    private CreateAssetHostRequest withMemory(String memoryGb) {
        CreateAssetHostRequest request = validRequest();
        request.setMemoryGb(new BigDecimal(memoryGb));
        return request;
    }

    private CreateAssetHostRequest withDisk(String diskGb) {
        CreateAssetHostRequest request = validRequest();
        request.setDiskGb(new BigDecimal(diskGb));
        return request;
    }

    @Test
    @DisplayName("合法登记：写入台账，状态为待确认，不声称在线/健康，不写心跳")
    void createsHostWithUnconfirmedStatus() {
        CreateAssetHostRequest request = validRequest();
        request.setCpuCores(new BigDecimal("4"));
        request.setMemoryGb(new BigDecimal("8"));
        request.setDiskGb(new BigDecimal("100"));

        AssetHost host = service.createHost(request);

        verify(mapper).insert(host);
        assertEquals("app-01", host.getHostname());
        assertEquals("192.0.2.3", host.getIp());
        assertEquals("LINUX", host.getOsType());
        assertEquals("VM", host.getHostType());
        assertEquals(ENV_ID, host.getEnvId());
        assertEquals(POOL_ID, host.getResourcePoolId());
        assertEquals(new BigDecimal("4"), host.getCpuCores());
        assertEquals(new BigDecimal("8"), host.getMemoryGb());
        assertEquals(new BigDecimal("100"), host.getDiskGb());

        assertEquals("UNKNOWN", host.getHostStatus(), "新登记主机不得默认声称在线");
        assertEquals("UNKNOWN", host.getAgentStatus(), "新登记主机不得默认声称 Agent 在线");
        assertNull(host.getLastHeartbeat(), "登记不等于心跳，不得写心跳时间");
    }

    @Test
    @DisplayName("规格留空：入库为 0（未登记规格），其余状态仍为待确认")
    void leavesUnspecifiedSpecsAsZero() {
        AssetHost host = service.createHost(validRequest());

        assertEquals(BigDecimal.ZERO, host.getCpuCores());
        assertEquals(BigDecimal.ZERO, host.getMemoryGb());
        assertEquals(BigDecimal.ZERO, host.getDiskGb());
        assertEquals("UNKNOWN", host.getHostStatus());
        assertEquals("UNKNOWN", host.getAgentStatus());
        assertNull(host.getLastHeartbeat());
    }

    @Test
    @DisplayName("资源池可选：不填资源池也能登记")
    void allowsMissingResourcePool() {
        CreateAssetHostRequest request = validRequest();
        request.setResourcePoolId(null);

        AssetHost host = service.createHost(request);

        verify(mapper).insert(host);
        assertNull(host.getResourcePoolId());
    }

    @Test
    @DisplayName("必填校验：主机名/IP/系统类型/主机类型/环境为空均被拒绝，且不落库")
    void rejectsMissingRequiredFields() {
        CreateAssetHostRequest blankHostname = validRequest();
        blankHostname.setHostname("   ");
        assertValidationError("主机名不能为空", () -> service.createHost(blankHostname));

        CreateAssetHostRequest blankIp = validRequest();
        blankIp.setIp(null);
        assertValidationError("IP 地址不能为空", () -> service.createHost(blankIp));

        CreateAssetHostRequest blankOsType = validRequest();
        blankOsType.setOsType(" ");
        assertValidationError("系统类型不能为空", () -> service.createHost(blankOsType));

        CreateAssetHostRequest blankHostType = validRequest();
        blankHostType.setHostType("");
        assertValidationError("主机类型不能为空", () -> service.createHost(blankHostType));

        CreateAssetHostRequest blankEnv = validRequest();
        blankEnv.setEnvId(null);
        assertValidationError("环境不能为空", () -> service.createHost(blankEnv));

        verify(mapper, never()).insert(any(AssetHost.class));
    }

    @Test
    @DisplayName("长度校验：主机名 129 字符被拒，128 字符通过；IP 超 64 字符被拒")
    void enforcesLengthLimits() {
        CreateAssetHostRequest tooLongHostname = validRequest();
        tooLongHostname.setHostname("h".repeat(129));
        assertValidationError("主机名长度不能超过 128 个字符", () -> service.createHost(tooLongHostname));

        CreateAssetHostRequest maxHostname = validRequest();
        maxHostname.setHostname("h".repeat(128));
        assertEquals("h".repeat(128), service.createHost(maxHostname).getHostname());

        CreateAssetHostRequest tooLongIp = validRequest();
        tooLongIp.setIp("192.0.2.3".concat("0".repeat(60)));
        assertValidationError("IP 地址长度不能超过 64 个字符", () -> service.createHost(tooLongIp));

        CreateAssetHostRequest tooLongOsType = validRequest();
        tooLongOsType.setOsType("O".repeat(33));
        assertValidationError("系统类型长度不能超过 32 个字符", () -> service.createHost(tooLongOsType));

        CreateAssetHostRequest tooLongHostType = validRequest();
        tooLongHostType.setHostType("T".repeat(33));
        assertValidationError("主机类型长度不能超过 32 个字符", () -> service.createHost(tooLongHostType));
    }

    @Test
    @DisplayName("IP 格式校验：非法写法被拒，IPv4/IPv6 字面量通过，首尾空白先被裁剪")
    void validatesIpFormat() {
        Stream.of("999.1.1.1", "10.10.1", "10.10.1.40.5", "010.1.1.1", "host.example.com", "::1::2", "1:2:3:4:5:6:7:8:9")
                .forEach(bad -> {
                    CreateAssetHostRequest request = validRequest();
                    request.setIp(bad);
                    assertValidationError("IP 地址格式不正确", () -> service.createHost(request));
                });

        CreateAssetHostRequest ipv6 = validRequest();
        ipv6.setIp("2001:db8::1");
        assertEquals("2001:db8::1", service.createHost(ipv6).getIp());

        // 首尾空白先裁剪再校验/查重，避免 " 192.0.2.3 " 绕过唯一性
        CreateAssetHostRequest padded = validRequest();
        padded.setIp(" 192.0.2.3 ");
        padded.setHostname(" app-02 ");
        AssetHost host = service.createHost(padded);
        assertEquals("192.0.2.3", host.getIp());
        assertEquals("app-02", host.getHostname());
    }

    @Test
    @DisplayName("IPv6 单冒号边界：首尾单冒号与非法压缩语法不得放行")
    void rejectsSingleColonAtIpv6Boundaries() {
        // 这四类写法在旧的"跳过空分组"实现里会被误判为合法，必须逐个守住
        Stream.of(":1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:8:", ":1::2", "1::2:")
                .forEach(bad -> {
                    CreateAssetHostRequest request = validRequest();
                    request.setIp(bad);
                    assertValidationError("IP 地址格式不正确", () -> service.createHost(request));
                });

        verify(mapper, never()).insert(any(AssetHost.class));
    }

    @Test
    @DisplayName("非 ASCII 数字/字母：直接调 service 也拒绝，且不触达 insert")
    void rejectsNonAsciiIpCharactersBeforeInsert() {
        // Java 的 Character.isDigit / Character.digit(c,16) 会把以下字符当成数字/十六进制，
        // 必须在入库前逐个挡住，不能只靠数据库。
        Stream.of("١٢٧.٠.٠.١",            // 阿拉伯-印度数字
                        "１２７.０.０.１",  // 全角数字
                        "Ａ::1",            // 全角拉丁字母
                        "127.0.0.１",       // 半角/全角混排
                        "2001:ｄｂ８::1",    // IPv6 组内全角十六进制
                        "２００１::１")      // IPv6 全角数字
                .forEach(bad -> {
                    CreateAssetHostRequest request = validRequest();
                    request.setIp(bad);
                    assertValidationError("IP 地址格式不正确", () -> service.createHost(request));
                });

        verify(mapper, never()).insert(any(AssetHost.class));
    }

    @Test
    @DisplayName("IP 格式判定：合法与非法字面量的边界（与前端共用同一组样本）")
    void ipLiteralBoundaries() {
        Stream.of("192.0.2.1", "0.0.0.0", "255.255.255.255")
                .forEach(good -> assertTrue(IpLiterals.isIpLiteral(good), good));

        // IPv6：全压缩、前导/尾随压缩、完整八组、压缩后不足八组，大小写 ASCII 十六进制均合法
        Stream.of("::", "::1", "1::", "2001:db8::1", "fe80::1", "abcd::1234:5678",
                        "2001:db8:0:0:0:0:0:1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::",
                        "2001:DB8::1", "FE80::1", "ABCD:EF01::1", "2001:dB8::1")
                .forEach(good -> assertTrue(IpLiterals.isIpLiteral(good), good));

        // IPv4 非法
        Stream.of("256.1.1.1", "192.0.2.3 ", "1.2.3.4.5", "010.1.1.1", "1.2.3", "1.2.3.4.5.6")
                .forEach(bad -> assertFalse(IpLiterals.isIpLiteral(bad), bad));

        // IPv6 非法：单冒号首尾、重复压缩、超过八组、压缩后仍凑满八组、非法分组、zone id
        Stream.of(":1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:8:", ":1::2", "1::2:", "::1::2",
                        "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "1:2:3:4:5:6:7", ":::", ":",
                        "12345::1", "2001:db8::zz", "fe80::1%eth0")
                .forEach(bad -> assertFalse(IpLiterals.isIpLiteral(bad), bad));

        // 非 ASCII 数字/字母：Java 的 Character.isDigit / Character.digit(c,16) 会误收，必须按原样拒绝
        Stream.of("١٢٧.٠.٠.١", "１２７.０.０.１", "Ａ::1", "127.0.0.１",
                        "2001:ｄｂ８::1", "２００１::１")
                .forEach(bad -> assertFalse(IpLiterals.isIpLiteral(bad), bad));

        assertFalse(IpLiterals.isIpLiteral(""));
        assertFalse(IpLiterals.isIpLiteral(null));
    }

    @Test
    @DisplayName("规格范围与精度：显式 0、负数、越界、超过 2 位小数都被拒绝，且不落库")
    void rejectsSpecsOutsideContract() {
        assertValidationError("CPU 核数必须大于 0", () -> service.createHost(withCpu("0")));
        assertValidationError("CPU 核数必须大于 0", () -> service.createHost(withCpu("-1")));
        assertValidationError("CPU 核数不能超过 999999.99", () -> service.createHost(withCpu("1000000")));
        // 0.001 是正数但小数位过多，不能被静默舍成 0 后当成"未填写"
        assertValidationError("CPU 核数最多 2 位小数", () -> service.createHost(withCpu("0.001")));
        assertValidationError("CPU 核数最多 2 位小数", () -> service.createHost(withCpu("2.500")));

        assertValidationError("内存容量不能超过 99999999.99", () -> service.createHost(withMemory("100000000")));
        assertValidationError("内存容量必须大于 0", () -> service.createHost(withMemory("0")));
        assertValidationError("磁盘容量不能超过 99999999.99", () -> service.createHost(withDisk("100000000")));
        assertValidationError("磁盘容量最多 2 位小数", () -> service.createHost(withDisk("1.234")));

        verify(mapper, never()).insert(any(AssetHost.class));
    }

    @Test
    @DisplayName("规格边界值：上限与两位小数被接受，留空仍按未填写落成 0")
    void acceptsSpecBoundaryValues() {
        CreateAssetHostRequest request = validRequest();
        request.setCpuCores(new BigDecimal("999999.99"));
        request.setMemoryGb(new BigDecimal("99999999.99"));
        request.setDiskGb(new BigDecimal("0.01"));

        AssetHost host = service.createHost(request);

        assertEquals(new BigDecimal("999999.99"), host.getCpuCores());
        assertEquals(new BigDecimal("99999999.99"), host.getMemoryGb());
        assertEquals(new BigDecimal("0.01"), host.getDiskGb());
    }

    @Test
    @DisplayName("关联对象校验：环境或资源池不存在时拒绝登记")
    void rejectsMissingRelatedObjects() {
        CreateAssetHostRequest unknownEnv = validRequest();
        unknownEnv.setEnvId(999L);
        assertValidationError("环境不存在: 999", () -> service.createHost(unknownEnv));

        CreateAssetHostRequest unknownPool = validRequest();
        unknownPool.setResourcePoolId(999L);
        assertValidationError("资源池不存在: 999", () -> service.createHost(unknownPool));

        verify(mapper, never()).insert(any(AssetHost.class));
    }

    @Test
    @DisplayName("重复校验：主机名与 IP 各自重复都被拒绝，且查到重复后不再落库")
    void rejectsDuplicates() {
        stubDuplicateResults(1L, 0L);
        assertValidationError("主机名已存在: app-01", () -> service.createHost(validRequest()));

        stubDuplicateResults(0L, 1L);
        assertValidationError("IP 地址已存在: 192.0.2.3", () -> service.createHost(validRequest()));

        verify(mapper, never()).insert(any(AssetHost.class));
    }

    @Test
    @DisplayName("并发兜底：唯一约束冲突转成参数校验错误，不冒泡成 500")
    void convertsUniqueViolationToValidationError() {
        when(mapper.insert(any(AssetHost.class)))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint \"asset_host_hostname_key\""));

        BizException error = assertThrows(BizException.class, () -> service.createHost(validRequest()));

        assertEquals("主机名或 IP 已存在，登记被拒绝", error.getMessage());
    }

    @Test
    @DisplayName("查重先于落库：主机名与 IP 各查一次，且只发生一次 insert")
    void checksBothUniquenessRulesBeforeInsert() {
        service.createHost(validRequest());

        verify(mapper, times(2)).selectCount(any());
        verify(mapper, times(1)).insert(any(AssetHost.class));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    @DisplayName("结构性保证：登记路径不持有运行实例依赖，无法顺手创建实例")
    void registrationPathCannotCreateRuntimeInstances() throws Exception {
        Field[] fields = AssetHostService.class.getDeclaredFields();
        for (Field field : fields) {
            assertFalse(field.getType().getSimpleName().contains("RuntimeServiceInstance"),
                    "登记路径不应持有运行实例相关的依赖: " + field.getName());
        }

        AssetHost host = service.createHost(validRequest());
        assertNotNull(host);
        assertNull(host.getLastHeartbeat());
        assertEquals("UNKNOWN", host.getHostStatus());
    }

    private void assertValidationError(String expectedMessage, Runnable action) {
        BizException error = assertThrows(BizException.class, action::run);
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().startsWith(expectedMessage),
                "期望错误信息以 [" + expectedMessage + "] 开头，实际为 [" + error.getMessage() + "]");
    }
}
