package com.zhiflow.common.core.util;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.dto.ClientLocation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class ClientInfoUtils {

    public ClientLocation getClientLocation() {
        return WebUtils.getRequest()
                .map(ClientInfoUtils::fromRequest)
                .orElseGet(ClientLocation::empty);
    }

    public ClientLocation getClientLocation(HttpServletRequest request) {
        if (request == null) {
            return ClientLocation.empty();
        }
        return fromRequest(request);
    }

    /**
     * 开关 {@value ClientIpResolver#ENABLED_KEY} 关（现状）：整组原样取网关注入的 X-Client-* 头。
     * <p>开：IP 走 {@link ClientIpResolver#trusted}；省市区 / 经纬度 / ISP 只在 {@code X-Client-IP} 与它是同一地址时才采信——
     * 不经网关的直连路径（{@code /im/ws}、{@code /portal/} 等）上 X-Client-* 是客户端自己带来的，原样到达服务，
     * 两者对不上就当没有定位，不让伪造的位置落库。
     */
    private ClientLocation fromRequest(HttpServletRequest request) {
        if (ClientIpResolver.enabled()) {
            String ip = ClientIpResolver.trusted(request);
            if (!ClientIpResolver.sameAddress(request.getHeader(CommonConstants.CLIENT_IP), ip)) {
                return ClientLocation.builder().ip(nullIfBlank(ip)).build();
            }
            return locationHeaders(request).ip(nullIfBlank(ip)).build();
        }
        return locationHeaders(request)
                .ip(nullIfBlank(request.getHeader(CommonConstants.CLIENT_IP)))
                .build();
    }

    private ClientLocation.ClientLocationBuilder locationHeaders(HttpServletRequest request) {
        return ClientLocation.builder()
                .province(decodeHeader(request.getHeader(CommonConstants.CLIENT_PROVINCE)))
                .city(decodeHeader(request.getHeader(CommonConstants.CLIENT_CITY)))
                .district(decodeHeader(request.getHeader(CommonConstants.CLIENT_DISTRICT)))
                .longitude(parseDouble(request.getHeader(CommonConstants.CLIENT_LNG)))
                .latitude(parseDouble(request.getHeader(CommonConstants.CLIENT_LAT)))
                .isp(decodeHeader(request.getHeader(CommonConstants.CLIENT_ISP)))
                .areaCode(nullIfBlank(request.getHeader(CommonConstants.CLIENT_AREA_CODE)));
    }

    private String nullIfBlank(String value) {
        return StrUtil.isBlank(value) ? null : value;
    }

    /**
     * 中文头解码：网关 IpLocationGlobalFilter 对省/市/区/ISP 做了 URLEncoder 编码
     * （HTTP 头裸中文会被替换成 '?'）。兼容旧网关混发期：解码失败回退原值；
     * 纯 '?' 的残值（旧网关编码丢失产物）按无数据处理，避免脏值入库。
     */
    private String decodeHeader(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            decoded = value;
        }
        if (StrUtil.isBlank(decoded) || decoded.chars().allMatch(c -> c == '?')) {
            return null;
        }
        return decoded;
    }

    private Double parseDouble(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException _) {
            log.debug("Failed to parse coordinate value: {}", value);
            return null;
        }
    }
}
