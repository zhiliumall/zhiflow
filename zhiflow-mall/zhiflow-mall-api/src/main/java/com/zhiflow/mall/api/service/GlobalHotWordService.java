package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.GlobalHotWord;

import java.util.List;

/**
 * 搜索页热搜词。热词与热度只在后台 {@code /mall/hotword} 维护，C 端只读。
 */
public interface GlobalHotWordService extends IService<GlobalHotWord> {

	/**
	 * 热搜词前 10（按热度降序），供 {@code /globalhotword/getTop} 使用。短 TTL 缓存，后台改完最多一分钟可见。
	 */
	List<GlobalHotWord> listTop10();
}
