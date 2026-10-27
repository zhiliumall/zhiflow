package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.dto.UserDashboardDTO;
import com.zhiflow.mall.api.service.UserDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/user")
@Tag(description = "个人中心面板", name = "个人中心面板API")
public class UserDashboardController {
    private final UserDashboardService userDashboardService;
    /**
     * 用户面板聚合接口
     */
    @Operation(summary = "用户面板聚合接口")
    @GetMapping("/dashboard")
    @ApiLogin
    public R<UserDashboardDTO> getUserDashboard() {
        String userId = ThirdSessionHolder.getUserId();
        UserDashboardDTO userDashboard = userDashboardService.getUserDashboard(userId);
        return R.ok(userDashboard);
    }

}
