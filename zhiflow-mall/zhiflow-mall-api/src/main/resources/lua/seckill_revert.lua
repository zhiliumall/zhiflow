-- seckill_revert.lua
-- KEYS[1]: 命中桶的 key
-- KEYS[2]: 该活动的桶代号 key
-- ARGV[1]: 回补数量
-- ARGV[2]: 扣减时记下的代号
-- 返回：回补后的桶余量；不满足回补条件时返回 -1，且不做任何写入
--
-- 两道判据，缺一不可：
--
-- 1) 桶必须还在。
--    不能直接 INCRBY —— 它会在 key 不存在时把 key 建出来，且新建的 key **没有 TTL**。
--    admin 预热用「bucket 0 做 SETNX leader，已存在就跳过整轮预热」判活动是否在跑
--    （SeckillStockPrewarmService#loadBuckets），一个永不过期的孤儿 bucket 0 会让该活动
--    下一次预热被静默跳过 —— 也就是把活动的库存预热废掉。
--    取消链路距离下单可能过去很久，正是最容易撞上「桶已过期」的调用方。
--
-- 2) 代号必须对得上。
--    只判「桶还在」是不够的：管理员关闭再启用活动会 clear 掉 10 个桶、再按 limitNum
--    **全额重建**，于是同一个 seckillInfoId 会有好几代桶。上一代的订单在新一代被取消时，
--    EXISTS 对新桶为真 → 名额补进新一代 → 该轮可售数超过 limitNum（凭空多出配额）。
--    代号与桶同生同灭（预热时铸新的、clearBySeckillInfoId 时一并删），
--    对不上就说明「这不是我当初扣的那一代」，不补。
--
-- INCRBY 作用于已存在的 key 不会重置 TTL，桶的过期时刻保持不变。
--
-- 2026-08-14 在真实 valkey 上跑过这四种时序，结果如下（不是推断）：
--   桶不存在        → -1，且**没有建出孤儿 key**
--   代号一致        → 10 变 15，且 TTL 仍是 600（未被重置）
--   代号已换代      → -1，桶余量纹丝不动
--   代号键丢失      → -1，无写入

if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end

local gen = redis.call('GET', KEYS[2])
if not gen or gen ~= ARGV[2] then
    return -1
end

return redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
