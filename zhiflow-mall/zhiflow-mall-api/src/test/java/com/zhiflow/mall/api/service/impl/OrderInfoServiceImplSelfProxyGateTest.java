package com.zhiflow.mall.api.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D67 源码闸：{@code OrderInfoServiceImpl} 内部调用 {@code notifyOrder} 必须走 {@code self.}（Spring 代理），
 * 不得写成 {@code this.}。
 *
 * <p>为什么这条值得一道闸：{@code notifyOrder} 上挂着 {@code @Transactional} 与 {@code @DistributedLock}，
 * 而它发布的 {@code OrderPaidEvent} 由 {@code OrderEventListener#handleOrderPaid} 以
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 接收（未开 fallbackExecution）。
 * 一旦写成 {@code this.notifyOrder(...)}，自调用绕过代理 → 注解全失效 → 事件在无事务下发布 →
 * AFTER_COMMIT 监听器**静默丢弃**：不发分销佣金、不发推荐佣金、不发通知，日志里连异常都没有。
 * 症状只会以「某些订单没佣金」的形式出现在账上，极难回溯，所以用源码断言钉死。
 *
 * <p>历史：{@code orderCancelV2} 曾是 {@code this.}，而兄弟方法 {@code orderCancel} / {@code balancePay}
 * 一直是 {@code self.}——同一个类里两种写法并存，正是这类缺陷的温床。
 */
class OrderInfoServiceImplSelfProxyGateTest {

    private static final Path SRC = Paths.get(
            "src/main/java/com/zhiflow/mall/api/service/impl/OrderInfoServiceImpl.java");

    @Test
    void noSelfInvocationOfNotifyOrder() throws Exception {
        assertTrue(Files.exists(SRC), "找不到 OrderInfoServiceImpl 源码：" + SRC.toAbsolutePath());
        List<String> lines = Files.readAllLines(SRC);

        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // 注释行只是在解释这条规则本身，不算违规
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }
            if (line.contains("this.notifyOrder")) {
                offenders.add((i + 1) + ": " + trimmed);
            }
        }

        assertEquals(List.of(), offenders,
                "D67：notifyOrder 必须用 self.（走代理）调用，下列行用了 this. 会让 @Transactional/"
                        + "@DistributedLock 失效并导致 OrderPaidEvent 被 AFTER_COMMIT 监听器静默丢弃");
    }

    /** 反向断言：确实存在 self.notifyOrder 调用，防止有人靠删调用来"修绿"上面那条 */
    @Test
    void notifyOrderIsStillInvokedViaProxy() throws Exception {
        String code = Files.readString(SRC);
        assertTrue(code.contains("self.notifyOrder("),
                "OrderInfoServiceImpl 里应当仍有 self.notifyOrder(...) 调用");
    }
}
