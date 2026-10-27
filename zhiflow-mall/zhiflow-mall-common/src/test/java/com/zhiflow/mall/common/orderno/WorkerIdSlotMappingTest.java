package com.zhiflow.mall.common.orderno;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽位 → 机器位的映射，以及「不同机器位不撞号」这条 T2 的核心承诺。
 */
class WorkerIdSlotMappingTest {

    /** slot 0..1023 必须一一映射到 (dataCenterId, workerId) 且不重复、不越界。 */
    @Test
    void slotMapsOntoMachineBitsBijectively() {
        Set<String> seen = new HashSet<>();
        for (int slot = 0; slot < RegistryWorkerIdAssigner.TOTAL_SLOTS; slot++) {
            long dc = slot / RegistryWorkerIdAssigner.WORKER_ID_RANGE;
            long worker = slot % RegistryWorkerIdAssigner.WORKER_ID_RANGE;

            assertTrue(dc >= 0 && dc < 32, "dataCenterId 是 5 bit，必须落在 0..31，实际 " + dc);
            assertTrue(worker >= 0 && worker < 32, "workerId 是 5 bit，必须落在 0..31，实际 " + worker);
            assertTrue(seen.add(dc + ":" + worker), "slot " + slot + " 的机器位组合重复了");
        }
        assertEquals(RegistryWorkerIdAssigner.TOTAL_SLOTS, seen.size(),
                "1024 个槽位应恰好用满 5+5 bit 的全部组合");
    }

    /**
     * T2 的核心承诺：机器位不同 ⇒ 号必然不同。
     *
     * <p>用两个不同机器位的生成器**交替**各发 5 万个号（共 10 万），断言零重复。
     * 交替是关键——同一毫秒内两个生成器的 sequence 都从 0 起步，若机器位没进到号里，
     * 这里会立刻撞出一堆重复。这正是 T2 之前 mall-api 与 mall-admin 同用 (0,0) 的处境。
     */
    @Test
    void distinctMachineBitsNeverCollideAcrossOneHundredThousandIds() {
        OrderNoGenerator a = generator(1L, 0L);
        OrderNoGenerator b = generator(2L, 0L);

        int perGenerator = 50_000;
        Set<String> all = new HashSet<>(perGenerator * 2);
        for (int i = 0; i < perGenerator; i++) {
            assertTrue(all.add(a.next()), "生成器 A 产出重复号（第 " + i + " 轮）");
            assertTrue(all.add(b.next()), "生成器 B 与 A 撞号（第 " + i + " 轮）——机器位没有进到号里");
        }

        assertEquals(perGenerator * 2, all.size(), "10 万个号必须两两不同");
    }

    /** 号码格式不受机器位影响：仍是 19 位纯数字，且机器位能原样反解出来。 */
    @Test
    void machineBitsAreEncodedWithoutChangingFormat() {
        for (long dc : new long[] { 0L, 1L, 31L }) {
            for (long worker : new long[] { 0L, 1L, 31L }) {
                String orderNo = generator(worker, dc).next();

                assertEquals(19, orderNo.length(), "长度必须仍是 19：" + orderNo);
                assertTrue(orderNo.matches("\\d{19}"), "必须仍是纯数字：" + orderNo);

                long id = Long.parseLong(orderNo);
                assertEquals(dc, (id >> 17) & 0x1F, "dataCenterId 应落在第 17-21 位");
                assertEquals(worker, (id >> 12) & 0x1F, "workerId 应落在第 12-16 位");
            }
        }
    }

    private static OrderNoGenerator generator(long workerId, long dataCenterId) {
        return new OrderNoGenerator(new FixedWorkerIdAssigner(workerId, dataCenterId),
                new OrderNoMetrics(null), new NoOpOrderNoFailureSink());
    }
}
