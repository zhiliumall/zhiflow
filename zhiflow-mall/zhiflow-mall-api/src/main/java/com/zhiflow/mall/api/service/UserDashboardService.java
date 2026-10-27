package com.zhiflow.mall.api.service;

import com.zhiflow.mall.api.dto.UserDashboardDTO;

/**
 * 用户仪表板服务接口
 *
 */
public interface UserDashboardService {
    UserDashboardDTO getUserDashboard(String userId);
}