package com.infrahub.release.dto;

import com.infrahub.release.validation.IpLiteral;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 主机手动登记请求。
 *
 * <p>只登记资产台账信息。登记动作不建立 SSH 连接（连接参数在 asset_host_ssh_config，明文凭据不在本接口范围内）、
 * 不探测服务器、不安装 Agent、不创建运行实例、不写 last_heartbeat。
 *
 * <h2>文本字段的空白契约</h2>
 * 主机名/IP/系统类型/主机类型的首尾空白在反序列化时就被裁掉（见各 setter），因此 {@code @Valid} 校验的是
 * 裁剪后的值。也就是说 " host-a " 与 "host-a" 对接口是同一个值：既与前端提交前先 trim 的行为一致，
 * 也避免"前端放行、后端拒绝"的分裂。纯空白等同于未填写。
 *
 * <h2>规格字段的取值范围与精度契约</h2>
 * 规格（CPU/内存/磁盘）允许留空：留空表示"未登记规格"，入库为 0，展示端按"未填写"呈现，不臆造容量数值。
 * 一旦填写就必须是一个明确的正数，取值必须与库表列宽一致：
 * <ul>
 *   <li>CPU 核数：{@code NUMERIC(8,2)} → 大于 0 且不超过 999999.99，最多 2 位小数；</li>
 *   <li>内存/磁盘容量：{@code NUMERIC(10,2)} → 大于 0 且不超过 99999999.99，最多 2 位小数。</li>
 * </ul>
 * 显式 0、负数、越界、超过 2 位小数（含正数但小数位过多、四舍五入后为 0 的写法如 0.001）都在这里被拒绝，
 * 不会静默折算成"未填写"，也不是等数据库报错——{@code AssetHostService} 入库前用同一套规则再挡一次。
 */
@Data
public class CreateAssetHostRequest {

    @NotBlank(message = "主机名不能为空")
    @Size(max = 128, message = "主机名长度不能超过 128 个字符")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$",
            message = "主机名只能包含字母、数字、点、下划线和连字符，且需以字母或数字开头")
    private String hostname;

    @NotBlank(message = "IP 地址不能为空")
    @Size(max = 64, message = "IP 地址长度不能超过 64 个字符")
    @IpLiteral(message = "IP 地址格式不正确，仅支持 IPv4 或 IPv6 字面量")
    private String ip;

    @NotNull(message = "环境不能为空")
    private Long envId;

    private Long resourcePoolId;

    @NotBlank(message = "系统类型不能为空")
    @Size(max = 32, message = "系统类型长度不能超过 32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_./+-]*$",
            message = "系统类型只能包含字母、数字、下划线、点、斜杠、加号和连字符，且需以字母或数字开头")
    private String osType;

    @NotBlank(message = "主机类型不能为空")
    @Size(max = 32, message = "主机类型长度不能超过 32 个字符")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_./+-]*$",
            message = "主机类型只能包含字母、数字、下划线、点、斜杠、加号和连字符，且需以字母或数字开头")
    private String hostType;

    @DecimalMin(value = "0", inclusive = false, message = "CPU 核数必须大于 0")
    @Digits(integer = 6, fraction = 2, message = "CPU 核数最多 6 位整数、2 位小数（不超过 999999.99）")
    private BigDecimal cpuCores;

    @DecimalMin(value = "0", inclusive = false, message = "内存容量必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "内存容量最多 8 位整数、2 位小数（不超过 99999999.99）")
    private BigDecimal memoryGb;

    @DecimalMin(value = "0", inclusive = false, message = "磁盘容量必须大于 0")
    @Digits(integer = 8, fraction = 2, message = "磁盘容量最多 8 位整数、2 位小数（不超过 99999999.99）")
    private BigDecimal diskGb;

    /** 首尾空白在反序列化时裁掉，纯空白视为未填写（交给 @NotBlank 报错） */
    public void setHostname(String hostname) {
        this.hostname = trimToNull(hostname);
    }

    /** 首尾空白在反序列化时裁掉，@IpLiteral 校验的是裁剪后的值 */
    public void setIp(String ip) {
        this.ip = trimToNull(ip);
    }

    public void setOsType(String osType) {
        this.osType = trimToNull(osType);
    }

    public void setHostType(String hostType) {
        this.hostType = trimToNull(hostType);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}