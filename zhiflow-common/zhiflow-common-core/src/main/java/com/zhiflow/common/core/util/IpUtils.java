package com.zhiflow.common.core.util;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/**
 * 获取IP方法
 *
 * @author ruoyi
 */
public class IpUtils {
    /**
     * 取用户 IP。开关 {@value ClientIpResolver#ENABLED_KEY} 开时走 {@link ClientIpResolver#trusted}；
     * 关时是下面的旧取法——XFF 优先且取整串，客户端带一个 {@code X-Forwarded-For} 就能伪造。
     */
    public static String getIpAddr(HttpServletRequest request) {
        if (request == null)
        {
            return "unknown";
        }
        if (ClientIpResolver.enabled())
        {
            return ClientIpResolver.trusted(request);
        }
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip))
        {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip))
        {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip))
        {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip))
        {
            ip = request.getHeader("X-Real-IP");
        }

        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip))
        {
            ip = request.getRemoteAddr();
        }

        return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
    }

    public static boolean internalIp(String ip) {
        byte[] addr = textToNumericFormatV4(ip);
        return internalIp(addr) || "127.0.0.1".equals(ip);
    }

    private static boolean internalIp(byte[] addr) {
        if (addr == null || addr.length < 2)
        {
            return true;
        }
        final byte b0 = addr[0];
        final byte b1 = addr[1];
        // 10.x.x.x/8
        final byte SECTION_1 = 0x0A;
        // 172.16.x.x/12
        final byte SECTION_2 = (byte) 0xAC;
        final byte SECTION_3 = (byte) 0x10;
        final byte SECTION_4 = (byte) 0x1F;
        // 192.168.x.x/16
        final byte SECTION_5 = (byte) 0xC0;
        final byte SECTION_6 = (byte) 0xA8;
        switch (b0)
        {
            case SECTION_1:
                return true;
            case SECTION_2:
                if (b1 >= SECTION_3 && b1 <= SECTION_4)
                {
                    return true;
                }
            case SECTION_5:
                switch (b1)
                {
                    case SECTION_6:
                        return true;
                }
            default:
                return false;
        }
    }

    /**
     * 将IPv4地址转换成字节
     *
     * @param text IPv4地址
     * @return byte 字节
     */
    public static byte[] textToNumericFormatV4(String text) {
        if (text.length() == 0)
        {
            return null;
        }

        byte[] bytes = new byte[4];
        String[] elements = text.split("\\.", -1);
        try
        {
            long l;
            int i;
            switch (elements.length)
            {
                case 1:
                    l = Long.parseLong(elements[0]);
                    if ((l < 0L) || (l > 4294967295L)) {
                        return null;
                    }
                    bytes[0] = (byte) (int) (l >> 24 & 0xFF);
                    bytes[1] = (byte) (int) ((l & 0xFFFFFF) >> 16 & 0xFF);
                    bytes[2] = (byte) (int) ((l & 0xFFFF) >> 8 & 0xFF);
                    bytes[3] = (byte) (int) (l & 0xFF);
                    break;
                case 2:
                    l = Integer.parseInt(elements[0]);
                    if ((l < 0L) || (l > 255L)) {
                        return null;
                    }
                    bytes[0] = (byte) (int) (l & 0xFF);
                    l = Integer.parseInt(elements[1]);
                    if ((l < 0L) || (l > 16777215L)) {
                        return null;
                    }
                    bytes[1] = (byte) (int) (l >> 16 & 0xFF);
                    bytes[2] = (byte) (int) ((l & 0xFFFF) >> 8 & 0xFF);
                    bytes[3] = (byte) (int) (l & 0xFF);
                    break;
                case 3:
                    for (i = 0; i < 2; ++i)
                    {
                        l = Integer.parseInt(elements[i]);
                        if ((l < 0L) || (l > 255L)) {
                            return null;
                        }
                        bytes[i] = (byte) (int) (l & 0xFF);
                    }
                    l = Integer.parseInt(elements[2]);
                    if ((l < 0L) || (l > 65535L)) {
                        return null;
                    }
                    bytes[2] = (byte) (int) (l >> 8 & 0xFF);
                    bytes[3] = (byte) (int) (l & 0xFF);
                    break;
                case 4:
                    for (i = 0; i < 4; ++i)
                    {
                        l = Integer.parseInt(elements[i]);
                        if ((l < 0L) || (l > 255L)) {
                            return null;
                        }
                        bytes[i] = (byte) (int) (l & 0xFF);
                    }
                    break;
                default:
                    return null;
            }
        }
        catch (NumberFormatException _)
        {
            return null;
        }
        return bytes;
    }

    public static String getHostIp() {
        try
        {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException _)
        {
        }
        return "127.0.0.1";
    }

    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException _) {
        }
        return "未知";
    }

	/**
	 * 通过IP获取地址信息。
	 * 优先从网关注入的 X-Client-* headers 读取（已由 IP2Region 解析），
	 * 无 HTTP 上下文时返回 null。
	 * @param ip IP地址（保留参数兼容性，实际从 header 读取）
	 * @return 地址文本，如 "福建省厦门市"
	 */
	public static String getAddresses(String ip) {
		try {
			var requestOpt = WebUtils.getRequest();
			if (requestOpt.isPresent()) {
				var loc = ClientInfoUtils.getClientLocation(requestOpt.get());
				if (loc.hasCity()) {
					StringBuilder sb = new StringBuilder();
					if (loc.getProvince() != null) sb.append(loc.getProvince());
					if (loc.getCity() != null) sb.append(loc.getCity());
					if (loc.getDistrict() != null) sb.append(loc.getDistrict());
					return sb.toString();
				}
			}
		} catch (Exception _) {
			// RequestContextHolder 可能不可用（异步线程等）
		}
		return null;
	}

	/**
	 * 通过IP获取省市。
	 * 优先从网关注入的 X-Client-* headers 读取（已由 IP2Region 解析），
	 * 无 HTTP 上下文时返回 null。
	 * @param ip IP地址（保留参数兼容性，实际从 header 读取）
	 * @return Map 包含 "pro"（省）和 "city"（市）
	 */
	public static Map<String, String> getProCity(String ip) {
		try {
			var requestOpt = WebUtils.getRequest();
			if (requestOpt.isPresent()) {
				var loc = ClientInfoUtils.getClientLocation(requestOpt.get());
				if (loc.hasCity()) {
					Map<String, String> map = new HashMap<>();
					map.put("pro", loc.getProvince());
					map.put("city", loc.getCity());
					return map;
				}
			}
		} catch (Exception _) {
			// RequestContextHolder 可能不可用
		}
		return null;
	}
}