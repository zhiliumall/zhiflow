package com.zhiflow.common.core.util;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.dto.ClientLocation;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientInfoUtilsTest {

    @Test
    void fromRequest_allHeadersPopulated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstants.CLIENT_IP, "120.36.174.100");
        request.addHeader(CommonConstants.CLIENT_PROVINCE, "福建省");
        request.addHeader(CommonConstants.CLIENT_CITY, "厦门市");
        request.addHeader(CommonConstants.CLIENT_DISTRICT, "思明区");
        request.addHeader(CommonConstants.CLIENT_LNG, "118.08942");
        request.addHeader(CommonConstants.CLIENT_LAT, "24.47983");
        request.addHeader(CommonConstants.CLIENT_ISP, "电信");
        request.addHeader(CommonConstants.CLIENT_AREA_CODE, "350203");

        ClientLocation loc = ClientInfoUtils.getClientLocation(request);

        assertEquals("120.36.174.100", loc.getIp());
        assertEquals("福建省", loc.getProvince());
        assertEquals("厦门市", loc.getCity());
        assertEquals("思明区", loc.getDistrict());
        assertEquals(118.08942, loc.getLongitude(), 0.0001);
        assertEquals(24.47983, loc.getLatitude(), 0.0001);
        assertEquals("电信", loc.getIsp());
        assertEquals("350203", loc.getAreaCode());
        assertTrue(loc.hasCoordinates());
        assertTrue(loc.hasCity());
    }

    @Test
    void fromRequest_noHeaders_returnsEmpty() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ClientLocation loc = ClientInfoUtils.getClientLocation(request);

        assertNull(loc.getIp());
        assertNull(loc.getProvince());
        assertNull(loc.getCity());
        assertNull(loc.getLongitude());
        assertNull(loc.getLatitude());
        assertFalse(loc.hasCoordinates());
        assertFalse(loc.hasCity());
    }

    @Test
    void fromRequest_nullRequest_returnsEmpty() {
        ClientLocation loc = ClientInfoUtils.getClientLocation(null);
        assertNotNull(loc);
        assertFalse(loc.hasCoordinates());
    }

    @Test
    void fromRequest_partialHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstants.CLIENT_PROVINCE, "广东省");
        request.addHeader(CommonConstants.CLIENT_CITY, "广州市");

        ClientLocation loc = ClientInfoUtils.getClientLocation(request);

        assertEquals("广东省", loc.getProvince());
        assertEquals("广州市", loc.getCity());
        assertNull(loc.getLongitude());
        assertFalse(loc.hasCoordinates());
        assertTrue(loc.hasCity());
    }

    @Test
    void fromRequest_invalidCoordinates() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CommonConstants.CLIENT_LNG, "not-a-number");
        request.addHeader(CommonConstants.CLIENT_LAT, "also-invalid");

        ClientLocation loc = ClientInfoUtils.getClientLocation(request);

        assertNull(loc.getLongitude());
        assertNull(loc.getLatitude());
        assertFalse(loc.hasCoordinates());
    }
}
