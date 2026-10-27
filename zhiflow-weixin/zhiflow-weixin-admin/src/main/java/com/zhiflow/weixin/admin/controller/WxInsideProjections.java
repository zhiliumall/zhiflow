package com.zhiflow.weixin.admin.controller;

import cn.binarywang.wx.miniapp.bean.shop.request.shipping.ContactBean;
import cn.binarywang.wx.miniapp.bean.shop.request.shipping.OrderKeyBean;
import cn.binarywang.wx.miniapp.bean.shop.request.shipping.PayerBean;
import cn.binarywang.wx.miniapp.bean.shop.request.shipping.ShippingListBean;
import cn.binarywang.wx.miniapp.bean.shop.request.shipping.WxMaOrderShippingInfoUploadRequest;
import cn.binarywang.wx.miniapp.bean.shop.response.WxMaOrderShippingInfoBaseResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.api.weixin.dto.WxJsapiSignatureInsideDTO;
import com.zhiflow.api.weixin.dto.WxMpUserInsideDTO;
import com.zhiflow.api.weixin.dto.WxOrderShippingContactDTO;
import com.zhiflow.api.weixin.dto.WxOrderShippingItemDTO;
import com.zhiflow.api.weixin.dto.WxOrderShippingKeyDTO;
import com.zhiflow.api.weixin.dto.WxOrderShippingResultDTO;
import com.zhiflow.api.weixin.dto.WxOrderShippingUploadDTO;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.weixin.common.entity.WxApp;
import com.zhiflow.weixin.common.entity.WxUser;
import me.chanjar.weixin.common.bean.WxJsapiSignature;
import me.chanjar.weixin.mp.bean.result.WxMpUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * weixin-admin 作为<b>被调方</b>时，持久化实体 / 微信 SDK 类型 ⇄ 契约投影 DTO 的映射。
 *
 * <p>[B7] 契约治理的落点：契约层不再出现 {@code @TableName} 实体与 {@code weixin-java-*} 的类型，
 * 于是「服务端改一个列名 / SDK 升级改一个字段名」从<b>静默丢字段</b>变成<b>编译错误</b>。
 *
 * <p>刻意写成手工映射而不是 {@code BeanUtil.copyProperties}：反射拷贝在字段名对不上的时候
 * 同样是静默的，而这份文件的全部价值就是让「哪些字段跨这条边界」可读、可 diff、可评审。
 *
 * <p>每个投影上刻意丢弃了哪些字段、为什么，写在各 DTO 类自己的 javadoc 里。
 */
public final class WxInsideProjections {

	private WxInsideProjections() {
	}

	// ===================================================================== WxUser

	/** 实体 → 投影。{@code phone} 的脱敏由 Jackson（REST）/ codec（gRPC）在发送侧完成，这里传原值。 */
	public static WxUserInsideDTO toDto(WxUser src) {
		if (src == null) {
			return null;
		}
		WxUserInsideDTO out = new WxUserInsideDTO();
		out.setId(src.getId());
		out.setAppId(src.getAppId());
		out.setAppType(src.getAppType());
		out.setOpenId(src.getOpenId());
		out.setUnionId(src.getUnionId());
		out.setMallUserId(src.getMallUserId());
		out.setNickName(src.getNickName());
		out.setHeadimgUrl(src.getHeadimgUrl());
		out.setPhone(src.getPhone());
		out.setSessionKey(src.getSessionKey());
		out.setTenantId(src.getTenantId());
		out.setSex(src.getSex());
		return out;
	}

	public static List<WxUserInsideDTO> toDtoList(List<WxUser> src) {
		if (src == null) {
			return null;
		}
		List<WxUserInsideDTO> out = new ArrayList<>(src.size());
		src.forEach(item -> out.add(toDto(item)));
		return out;
	}

	/**
	 * 投影 → 实体（{@code saveOrUpdate} 的写方向）。
	 *
	 * <p>⚠️ 投影里没有的列（{@code nickName} / {@code headimgUrl} …）
	 * 在这里保持 {@code null}。MyBatis-Plus 的默认更新策略是 {@code NOT_NULL} ——
	 * {@code null} 的字段不进 {@code SET} 子句，所以库里的现有值不会被清空。
	 * 这与迁移前一致：迁移前调用方回传的也是它自己那次请求里读到的对象。
	 */
	public static WxUser toEntity(WxUserInsideDTO src) {
		if (src == null) {
			return null;
		}
		WxUser out = new WxUser();
		out.setId(src.getId());
		out.setAppId(src.getAppId());
		out.setAppType(src.getAppType());
		out.setOpenId(src.getOpenId());
		out.setUnionId(src.getUnionId());
		out.setMallUserId(src.getMallUserId());
		out.setNickName(src.getNickName());
		out.setHeadimgUrl(src.getHeadimgUrl());
		out.setPhone(src.getPhone());
		out.setSessionKey(src.getSessionKey());
		out.setTenantId(src.getTenantId());
		out.setSex(src.getSex());
		return out;
	}

	// ===================================================================== WxApp

	/** 实体 → 投影。{@code secret / token / aesKey} 不在投影上，见 {@link WxAppInsideDTO}。 */
	public static WxAppInsideDTO toDto(WxApp src) {
		if (src == null) {
			return null;
		}
		WxAppInsideDTO out = new WxAppInsideDTO();
		out.setId(src.getId());
		out.setAppType(src.getAppType());
		out.setName(src.getName());
		out.setLogo(src.getLogo());
		out.setRemarks(src.getRemarks());
		out.setTenantId(src.getTenantId());
		return out;
	}

	// ===================================================================== SDK: 公众号用户

	public static WxMpUserInsideDTO toDto(WxMpUser src) {
		if (src == null) {
			return null;
		}
		WxMpUserInsideDTO out = new WxMpUserInsideDTO();
		out.setSubscribe(src.getSubscribe());
		out.setOpenId(src.getOpenId());
		out.setNickname(src.getNickname());
		out.setLanguage(src.getLanguage());
		out.setHeadImgUrl(src.getHeadImgUrl());
		out.setSubscribeTime(src.getSubscribeTime());
		out.setUnionId(src.getUnionId());
		out.setRemark(src.getRemark());
		out.setGroupId(src.getGroupId());
		out.setTagIds(src.getTagIds() == null ? null : Arrays.asList(src.getTagIds()));
		out.setPrivileges(src.getPrivileges() == null ? null : Arrays.asList(src.getPrivileges()));
		out.setSubscribeScene(src.getSubscribeScene());
		out.setQrScene(src.getQrScene());
		out.setQrSceneStr(src.getQrSceneStr());
		return out;
	}

	// ===================================================================== SDK: 手机号

	// ===================================================================== SDK: JS-SDK 签名

	public static WxJsapiSignatureInsideDTO toDto(WxJsapiSignature src) {
		if (src == null) {
			return null;
		}
		WxJsapiSignatureInsideDTO out = new WxJsapiSignatureInsideDTO();
		out.setAppId(src.getAppId());
		out.setNonceStr(src.getNonceStr());
		out.setTimestamp(src.getTimestamp());
		out.setUrl(src.getUrl());
		out.setSignature(src.getSignature());
		return out;
	}

	// ===================================================================== SDK: 直播间

	// ===================================================================== 模板消息

	/** 供单测/判据构造对照数据用：JSON 文本 → hutool {@code JSONArray}。 */
	public static JSONArray parseContent(String contentJson) {
		return contentJson == null ? null : JSONUtil.parseArray(contentJson);
	}

	// ===================================================================== SDK: 小程序发货

	public static WxOrderShippingResultDTO toDto(WxMaOrderShippingInfoBaseResponse src) {
		if (src == null) {
			return null;
		}
		WxOrderShippingResultDTO out = new WxOrderShippingResultDTO();
		out.setErrCode(src.getErrCode());
		out.setErrMsg(src.getErrMsg());
		return out;
	}

	/** 投影 → SDK 请求对象（服务端整体转发给微信）。 */
	public static WxMaOrderShippingInfoUploadRequest toSdkRequest(WxOrderShippingUploadDTO src) {
		if (src == null) {
			return null;
		}
		WxMaOrderShippingInfoUploadRequest out = new WxMaOrderShippingInfoUploadRequest();
		out.setLogisticsType(src.getLogisticsType());
		out.setDeliveryMode(src.getDeliveryMode());
		out.setIsAllDelivered(src.getIsAllDelivered());
		out.setUploadTime(src.getUploadTime());
		WxOrderShippingKeyDTO key = src.getOrderKey();
		if (key != null) {
			OrderKeyBean bean = new OrderKeyBean();
			bean.setOrderNumberType(key.getOrderNumberType());
			bean.setTransactionId(key.getTransactionId());
			bean.setMchId(key.getMchId());
			bean.setOutTradeNo(key.getOutTradeNo());
			out.setOrderKey(bean);
		}
		if (src.getPayer() != null) {
			PayerBean payer = new PayerBean();
			payer.setOpenid(src.getPayer().getOpenid());
			out.setPayer(payer);
		}
		if (src.getShippingList() != null) {
			List<ShippingListBean> list = new ArrayList<>(src.getShippingList().size());
			for (WxOrderShippingItemDTO item : src.getShippingList()) {
				ShippingListBean bean = new ShippingListBean();
				bean.setTrackingNo(item.getTrackingNo());
				bean.setExpressCompany(item.getExpressCompany());
				bean.setItemDesc(item.getItemDesc());
				WxOrderShippingContactDTO contact = item.getContact();
				if (contact != null) {
					ContactBean contactBean = new ContactBean();
					contactBean.setConsignorContact(contact.getConsignorContact());
					contactBean.setReceiverContact(contact.getReceiverContact());
					bean.setContact(contactBean);
				}
				list.add(bean);
			}
			out.setShippingList(list);
		}
		return out;
	}
}
