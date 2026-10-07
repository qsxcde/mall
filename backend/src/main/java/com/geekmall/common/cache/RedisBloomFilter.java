package com.geekmall.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 基于 Redis Bitmap 的布隆过滤器（穿透防护的第一道闸门）。
 *
 * <p><b>为什么需要它</b>：空值缓存只能挡住「已经被查过一次」的无效请求，攻击者只要每次换一个
 * 随机 id（{@code /products/1} → {@code /products/99999999}），空值缓存就完全失效 ——
 * 每个新 id 依然是一次真实的数据库查询。布隆过滤器用极小的内存（百万级 id 约 1MB）
 * 记住「哪些 id 可能存在」，把这类请求在进入数据库之前直接挡掉。</p>
 *
 * <p><b>误判方向</b>：布隆过滤器只会「把不存在的判为可能存在」（假阳性），
 * 绝不会「把存在的判为不存在」（无假阴性）。因此：</p>
 * <ul>
 *   <li>判定「不存在」→ 可以放心直接返回 404，无需查库；</li>
 *   <li>判定「可能存在」→ 仍需查库（由空值缓存兜住重复查询）。</li>
 * </ul>
 *
 * <p><b>安全性设计</b>：如果 Redis 数据被清空（重启 / flushall），过滤器会连同
 * {@code ready} 标记一起消失。此时 {@link #isReady()} 返回 {@code false}，
 * 调用方应<b>跳过</b>布隆校验 —— 否则过滤器为空会把<b>所有</b>商品都判为「不存在」，
 * 造成全站 404 的严重事故。这个「未就绪即放行」的降级是刻意设计的。</p>
 */
@Slf4j
public class RedisBloomFilter {

    private static final String HASH_ALGORITHM = "MD5";

    private final StringRedisTemplate redisTemplate;
    private final String key;
    private final String readyKey;
    private final long bitSize;
    private final int hashCount;

    /**
     * @param redisTemplate    Redis 客户端
     * @param key              位图 key
     * @param readyKey         就绪标记 key（存在即表示位图可信）
     * @param expectedInsertions 预期元素个数，决定位图大小
     * @param fpp              期望误判率（如 0.01 表示 1%）
     */
    public RedisBloomFilter(StringRedisTemplate redisTemplate, String key, String readyKey,
                            long expectedInsertions, double fpp) {
        this.redisTemplate = redisTemplate;
        this.key = key;
        this.readyKey = readyKey;
        long n = Math.max(1, expectedInsertions);
        double p = Math.min(Math.max(fpp, 1e-9), 1.0);
        // m = -n·ln(p) / (ln2)²   k = (m/n)·ln2
        this.bitSize = Math.max(64, (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2))));
        this.hashCount = Math.max(1, (int) Math.round((double) bitSize / n * Math.log(2)));
    }

    /**
     * 位图是否已就绪。
     *
     * <p>未就绪时调用方必须跳过校验，不能把「空位图」当作「全部不存在」。</p>
     */
    public boolean isReady() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(readyKey));
        } catch (Exception ex) {
            log.warn("布隆过滤器就绪状态探测失败，本次按未就绪处理（跳过校验）：{}", ex.getMessage());
            return false;
        }
    }

    /** 判断元素「可能存在」（true）或「一定不存在」（false）；未就绪时一律返回 true（放行）。 */
    public boolean mightContain(String value) {
        if (value == null) {
            return false;
        }
        if (!isReady()) {
            return true;
        }
        try {
            List<Object> bits = redisTemplate.executePipelined(
                    (RedisCallback<Object>) connection -> {
                        for (long offset : offsets(value)) {
                            connection.stringCommands().getBit(rawKey(), offset);
                        }
                        return null;
                    });
            return bits.stream().allMatch(bit -> Boolean.TRUE.equals(bit));
        } catch (Exception ex) {
            // 过滤器故障不应阻断业务：退化为「放行」，由空值缓存继续兜底
            log.warn("布隆过滤器查询失败，本次放行：{}", ex.getMessage());
            return true;
        }
    }

    /** 加入一个元素。 */
    public void add(String value) {
        if (value == null) {
            return;
        }
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (long offset : offsets(value)) {
                    connection.stringCommands().setBit(rawKey(), offset, true);
                }
                return null;
            });
        } catch (Exception ex) {
            log.warn("布隆过滤器写入失败（不影响业务）：{}", ex.getMessage());
        }
    }

    /**
     * 重建：清空位图 → 批量写入 → 打上就绪标记。
     *
     * <p>就绪标记放在最后一步，保证「标记可见」时数据已经写完；
     * 若重建中途失败，标记不存在，流量会自动走「跳过校验」而不是全站 404。</p>
     */
    public void rebuild(Collection<String> values, Duration readyTtl) {
        try {
            redisTemplate.delete(key);
            redisTemplate.delete(readyKey);
            addAllRaw(values);
            if (readyTtl != null && !readyTtl.isZero() && !readyTtl.isNegative()) {
                redisTemplate.opsForValue().set(readyKey, "1", readyTtl);
            } else {
                redisTemplate.opsForValue().set(readyKey, "1");
            }
            log.info("布隆过滤器重建完成：key={}, 元素数={}, 位图={}bit, 哈希次数={}",
                    key, values == null ? 0 : values.size(), bitSize, hashCount);
        } catch (Exception ex) {
            log.error("布隆过滤器重建失败，将继续以「跳过校验」模式运行：{}", ex.getMessage());
        }
    }

    private void addAllRaw(Collection<String> values) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            RedisStringCommands commands = connection.stringCommands();
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                for (long offset : offsets(value)) {
                    commands.setBit(rawKey(), offset, true);
                }
            }
            return null;
        });
    }

    private byte[] rawKey() {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 双重哈希（Kirsch-Mitzenmacher）：用一次摘要派生两个独立哈希，
     * 第 i 个位置取 {@code h1 + i·h2}，无需做 k 次完整摘要运算。
     */
    private long[] offsets(String value) {
        byte[] digest = digest(value);
        long h1 = toLong(digest, 0);
        long h2 = toLong(digest, 8);
        return IntStream.range(0, hashCount)
                .mapToLong(i -> Math.floorMod(h1 + i * h2, bitSize))
                .toArray();
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 " + HASH_ALGORITHM, ex);
        }
    }

    private long toLong(byte[] bytes, int offset) {
        long result = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            result = (result << 8) | (bytes[offset + i] & 0xFFL);
        }
        return result;
    }
}
