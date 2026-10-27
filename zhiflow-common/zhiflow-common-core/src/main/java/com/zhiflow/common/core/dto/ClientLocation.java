package com.zhiflow.common.core.dto;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientLocation {
    private String ip;
    private String province;
    private String city;
    private String district;
    private Double longitude;
    private Double latitude;
    private String isp;
    private String areaCode;

    public static ClientLocation empty() {
        return new ClientLocation();
    }

    public boolean hasCoordinates() {
        return longitude != null && latitude != null
                && longitude != 0 && latitude != 0;
    }

    public boolean hasCity() {
        return StrUtil.isNotBlank(city);
    }
}
