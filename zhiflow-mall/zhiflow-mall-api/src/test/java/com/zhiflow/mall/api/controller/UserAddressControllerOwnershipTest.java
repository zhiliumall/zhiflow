package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.service.UserAddressService;
import com.zhiflow.mall.common.entity.UserAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 端越权修复 B 组 · 1：C 端 POST /mallapi/useraddress（收货地址新增 / 修改）。
 *
 * <p>原实现把 userId 强制成本人后直接 saveOrUpdate：请求体带别人地址的 id，就会按 {@code WHERE id=?}
 * 覆盖那一行、并把 user_id 改成自己（别人的地址被夺走）。
 */
@ExtendWith(MockitoExtension.class)
class UserAddressControllerOwnershipTest {

    private static final String ME = "me-001";

    @Mock
    private UserAddressService userAddressService;

    @InjectMocks
    private UserAddressController controller;

    @BeforeEach
    void login() {
        ThirdSession s = new ThirdSession();
        s.setUserId(ME);
        ThirdSessionHolder.setThirdSession(s);
    }

    @AfterEach
    void clear() {
        ThirdSessionHolder.clear();
    }

    private static UserAddress body(String id) {
        UserAddress b = new UserAddress();
        b.setId(id);
        b.setAreaId("440305");
        b.setUserName("收货人");
        b.setDetailInfo("某路 1 号");
        return b;
    }

    @Test
    @DisplayName("带别人的地址 id：拒绝，不写库")
    void rejectsOverwritingSomeoneElsesAddress() {
        UserAddress others = new UserAddress();
        others.setId("addr-9");
        others.setUserId("other-9");
        when(userAddressService.getById("addr-9")).thenReturn(others);

        R r = controller.save(body("addr-9"));

        assertFalse(r.isOk());
        assertEquals("无权操作", r.getMsg());
        verify(userAddressService, never()).saveOrUpdate(any());
        verify(userAddressService, never()).updateById(any());
        verify(userAddressService, never()).save(any());
    }

    @Test
    @DisplayName("改自己的地址：照常按 id 更新")
    void updatesOwnAddress() {
        UserAddress mine = new UserAddress();
        mine.setId("addr-1");
        mine.setUserId(ME);
        when(userAddressService.getById("addr-1")).thenReturn(mine);
        when(userAddressService.saveOrUpdate(any())).thenReturn(true);

        R r = controller.save(body("addr-1"));

        assertTrue(r.isOk());
        ArgumentCaptor<UserAddress> c = ArgumentCaptor.forClass(UserAddress.class);
        verify(userAddressService).saveOrUpdate(c.capture());
        assertEquals("addr-1", c.getValue().getId());
        assertEquals(ME, c.getValue().getUserId());
    }

    @Test
    @DisplayName("新增：请求体带的 userId 不认，一律取会话")
    void createIgnoresClientUserId() {
        when(userAddressService.saveOrUpdate(any())).thenReturn(true);
        UserAddress b = body(null);
        b.setUserId("other-9");

        controller.save(b);

        ArgumentCaptor<UserAddress> c = ArgumentCaptor.forClass(UserAddress.class);
        verify(userAddressService).saveOrUpdate(c.capture());
        assertNull(c.getValue().getId());
        assertEquals(ME, c.getValue().getUserId());
    }

    @Test
    @DisplayName("带一个库里没有的 id：按新增处理，主键由服务端生成（不让客户端指定主键）")
    void unknownIdIsTreatedAsNew() {
        when(userAddressService.getById("made-up")).thenReturn(null);
        when(userAddressService.saveOrUpdate(any())).thenReturn(true);

        controller.save(body("made-up"));

        ArgumentCaptor<UserAddress> c = ArgumentCaptor.forClass(UserAddress.class);
        verify(userAddressService).saveOrUpdate(c.capture());
        assertNull(c.getValue().getId());
        assertEquals(ME, c.getValue().getUserId());
    }
}
