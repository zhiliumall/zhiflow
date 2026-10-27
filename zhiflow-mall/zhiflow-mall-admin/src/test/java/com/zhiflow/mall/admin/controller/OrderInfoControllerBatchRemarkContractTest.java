package com.zhiflow.mall.admin.controller;

import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.common.dto.OrderRemarkBatchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderInfoControllerBatchRemarkContractTest {

    @Test
    void batchRemarkKeepsDedicatedWriteAuthorityAndAuditCoverage() throws Exception {
        Method method = OrderInfoController.class.getMethod("batchRemark", OrderRemarkBatchRequest.class);
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        PreAuthorize authority = method.getAnnotation(PreAuthorize.class);
        SysLog audit = method.getAnnotation(SysLog.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/remark/batch"}, mapping.value());
        assertNotNull(authority);
        assertEquals("@ato.hasAuthority('mall:orderinfo:edit')", authority.value());
        assertNotNull(audit);
    }
}
