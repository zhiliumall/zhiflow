package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.GlobalHotWord;

public interface GlobalHotWordService extends IService<GlobalHotWord> {

	/** 租户内是否已有同名热词（未删除的）；{@code excludeId} 为修改时的自身 id，新增传 null。 */
	boolean wordExists(String word, Long excludeId);
}
