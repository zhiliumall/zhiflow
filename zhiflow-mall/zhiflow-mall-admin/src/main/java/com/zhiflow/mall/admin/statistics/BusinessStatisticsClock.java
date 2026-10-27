package com.zhiflow.mall.admin.statistics;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Single owner of the business reporting calendar. */
@Component
public class BusinessStatisticsClock {
    public static final ZoneId REQUIRED_ZONE = ZoneId.of("Asia/Shanghai");
    private final String configuredZone;
    private final Clock clock;

    /**
     * 必须显式标 {@code @Autowired}：本类有两个构造器，Spring 在「多构造器且无标注」时会回退去找
     * 无参构造器，找不到就报 {@code No default constructor found} —— 实测会因此
     * 启动失败、崩溃循环。判据由 {@code BeanConstructorAuditTest} 静态守住。
     */
    @Autowired
    public BusinessStatisticsClock(@Value("${mall.statistics.business-time-zone}") String configuredZone) {
        this(configuredZone, Clock.system(REQUIRED_ZONE));
    }

    /** 仅供测试注入固定 {@link Clock}；不参与 Spring 注入决议。 */
    BusinessStatisticsClock(String configuredZone, Clock clock) {
        this.configuredZone = configuredZone;
        this.clock = clock;
    }

    @PostConstruct
    void validate() {
        if (!REQUIRED_ZONE.getId().equals(configuredZone)) {
            throw new IllegalStateException("mall.statistics.business-time-zone must be Asia/Shanghai");
        }
    }

    public ZoneId zone() { return REQUIRED_ZONE; }
    public LocalDateTime now() { return LocalDateTime.now(clock.withZone(REQUIRED_ZONE)); }
}
