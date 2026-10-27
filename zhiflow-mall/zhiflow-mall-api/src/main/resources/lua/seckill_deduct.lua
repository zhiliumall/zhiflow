-- seckill_deduct.lua
-- KEYS: 10 个桶的 key，按 idx（1..10）顺序
-- ARGV[1]: startIdx (1..10)
-- ARGV[2]: quantity
-- 策略：从 startIdx 开始顺时针最多尝试 10 个桶，第一个够扣的桶原子扣减
-- 返回：命中的桶索引（1..N）；所有桶都不足时返回 -1
-- 注：扣减命中桶的 idx 需要返回给调用方，revert 时才能精确回补原桶。

local startIdx = tonumber(ARGV[1])
local qty = tonumber(ARGV[2])
local n = #KEYS
for i = 0, n - 1 do
    local idx = ((startIdx - 1 + i) % n) + 1
    local cur = tonumber(redis.call('GET', KEYS[idx]) or '0')
    if cur >= qty then
        redis.call('DECRBY', KEYS[idx], qty)
        return idx
    end
end
return -1
