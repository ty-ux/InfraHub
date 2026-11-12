package com.infrahub.release.support;

/**
 * IP 字面量判定（IPv4 / IPv6）。
 *
 * <p>刻意不使用 {@code InetAddress.getByName}：对形如 "999.1.1.1" 的非法点分字符串它会退化成 DNS 解析。
 * 明确不支持 IPv6 zone id（fe80::1%eth0）、IPv4-mapped（::ffff:1.2.3.4）与前导零写法（010.1.1.1），
 * 避免把网络语义含糊的值写进台账。
 *
 * <p>明确只承认 ASCII 字符：IPv4 数字限 '0'..'9'，IPv6 分组限 '0'..'9'/'a'..'f'/'A'..'F'。
 * 不做 Unicode 规范化——形如全角 "１２７.０.０.１" 或 "Ａ::1" 的字符串按原样拒绝，不会被 NFKC 折成合法值。
 * 这与前端 {@code hostValidation.ts} 的 {@code [0-9A-Fa-f]} 规则一致。
 *
 * <p>控制器（{@code CreateAssetHostRequest#ip} 上的 {@code @IpLiteral}）与 service 入库前都调用这里，
 * 两侧共用同一实现而不是各写一份。前端 {@code src/utils/hostValidation.ts} 按同一份契约独立实现，
 * 用同一组边界样本互证。
 */
public final class IpLiterals {

    private IpLiterals() {
    }

    public static boolean isIpLiteral(String value) {
        return isIpv4Literal(value) || isIpv6Literal(value);
    }

    public static boolean isIpv4Literal(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                // 必须显式限定 ASCII：Character.isDigit 把阿拉伯-印度数字、全角数字也视为数字，
                // 会让 "١٢٧.٠.٠.١"、"１２７.０.０.１" 这类非契约字符串通过校验并写进台账。
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * IPv6 字面量判定。
     *
     * <p>按 RFC 4291 的文本形式判定：压缩只允许出现一次（{@code ::}），压缩处至少代表一组 0，
     * 因此显式分组必须少于 8 组；非压缩时必须正好 8 组。每一组必须是 1~4 位十六进制。
     * 单冒号只能出现在两组之间——首尾或连续的单冒号会产生空分组，一律拒绝。
     */
    public static boolean isIpv6Literal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') < 0 || value.indexOf('%') >= 0) {
            return false;
        }

        int compression = value.indexOf("::");
        boolean compressed = compression >= 0;
        if (compressed && value.indexOf("::", compression + 2) >= 0) {
            return false;
        }

        String high = compressed ? value.substring(0, compression) : value;
        String low = compressed ? value.substring(compression + 2) : "";

        int highGroups = countGroups(high);
        if (highGroups < 0) {
            return false;
        }
        if (!compressed) {
            return highGroups == 8;
        }
        int lowGroups = countGroups(low);
        if (lowGroups < 0) {
            return false;
        }
        return highGroups + lowGroups < 8;
    }

    /**
     * 统计以 ':' 分隔的十六进制分组数。
     *
     * @return 分组数；出现空分组（首尾冒号、连续单冒号）或非法分组时返回 -1
     */
    private static int countGroups(String section) {
        if (section.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String group : section.split(":", -1)) {
            if (group.isEmpty() || group.length() > 4) {
                return -1;
            }
            for (int i = 0; i < group.length(); i++) {
                char c = group.charAt(i);
                // 同上，Character.digit(c, 16) 接受全角字母等非 ASCII 十六进制字符（"Ａ::1"），
                // 这里只承认 ASCII 的 0-9 / a-f / A-F。
                boolean asciiHexDigit = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!asciiHexDigit) {
                    return -1;
                }
            }
            count++;
        }
        return count;
    }
}