package com.infrahub.release.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * **当前登记版本一致性**：把某个目标版本与当前实例集合逐个实例的登记版本作比较。
 *
 * <p>这是与 {@link DeployCoverage} 完全不同的事实：
 * <ul>
 *   <li>{@code DeployCoverage} 回答“当时这次执行覆盖了哪些实例”；</li>
 *   <li>本视图回答“现在这些实例在平台登记上分别是什么版本，是否都等于目标版本”。</li>
 * </ul>
 * 一次历史发布可以覆盖当时的全部实例（{@code FULL}），但其中一些实例此后可能被再次发布、
 * 回滚或改绑；此时本视图必须显示 {@code SOME_DIFFER}，页面不得再说“当前全部实例都在该版本”
 * 或“无需处理”。
 *
 * <p><b>证据边界</b>：登记版本来自平台实例记录，是**平台登记状态**，不是远端实际运行版本的
 * 实时探测结果。平台不会为了本视图连接远端。实例被移除后不再出现在 {@code instances} 里；
 * 实例被改绑服务/环境后也不再属于当前集合；主机改绑会在实例行的主机字段上反映。
 *
 * <p><b>来源校验</b>：{@code targetSourceType} 说明目标版本来自哪个来源。{@code sourceConsistent=false}
 * 表示来源订单缺失、来源服务/环境/版本与目标不一致；此时不给出“全部一致”的结论，只保留逐实例
 * 的登记事实供人工核对。
 */
@Data
public class VersionRegistrationConsistency {

    /** 当前全部实例的登记版本都等于目标版本。 */
    public static final String STATUS_ALL_MATCH = "ALL_MATCH";
    /** 至少一个实例登记为其他版本或未登记版本。 */
    public static final String STATUS_SOME_DIFFER = "SOME_DIFFER";
    /** 当前没有实例，无法比较。 */
    public static final String STATUS_NO_INSTANCES = "NO_INSTANCES";
    /** 目标版本或来源无法确认，不能给出一致性结论。 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 目标版本来自某张发布单本身（发布单详情）。 */
    public static final String SOURCE_RELEASE_ORDER = "RELEASE_ORDER";
    /** 目标版本来自当前版本指针，且指针来源是发布单。 */
    public static final String SOURCE_CURRENT_VERSION_RELEASE = "CURRENT_VERSION_RELEASE";
    /** 目标版本来自当前版本指针，且指针来源是回滚单。 */
    public static final String SOURCE_CURRENT_VERSION_ROLLBACK = "CURRENT_VERSION_ROLLBACK";
    /** 没有可确认的来源。 */
    public static final String SOURCE_NONE = "NONE";

    public static final String STATE_MATCH = "MATCH";
    public static final String STATE_DIFFERENT = "DIFFERENT";
    public static final String STATE_UNREGISTERED = "UNREGISTERED";
    public static final String STATE_UNKNOWN = "UNKNOWN";

    private String status;
    private String targetSourceType;
    private Long sourceOrderId;
    private String sourceOrderNo;

    private Long targetVersionId;
    private String targetVersionNo;
    /** 来源订单是否存在，且服务/环境/版本与目标一致。 */
    private boolean sourceConsistent;
    /** 来源不一致时面向操作者的原因；一致时为 null。 */
    private String sourceRejectionReason;

    private int currentInstanceCount;
    private int matchingCount;
    private int differingCount;
    private int unregisteredCount;

    /** 当前实例逐行登记情况（按实例 ID 升序）。 */
    private List<InstanceRegistration> instances = new ArrayList<>();

    /** 可展示的一句话表述；明确说明这是平台登记状态，不是远端实际运行版本。 */
    private String statement;
    private String disclaimer;

    public static final String DISCLAIMER =
            "登记版本来自平台实例记录，不是远端实际运行版本的实时探测结果；"
                    + "平台不会通过 SSH/HTTP 自动读取远端状态。";

    @Data
    public static class InstanceRegistration {
        private Long instanceId;
        private String instanceName;
        private Long hostId;
        private String hostName;
        private Long registeredVersionId;
        private String registeredVersionNo;
        /** MATCH / DIFFERENT / UNREGISTERED / UNKNOWN。 */
        private String state;
        private boolean matchesTarget;
    }
}
