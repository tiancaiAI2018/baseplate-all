package com.example.baseplate.adapter.out.redis;

import com.example.baseplate.api.port.out.IdGenerator;
import com.example.baseplate.api.port.out.OneTimeTokenStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 一次性链接：用途域校验 + DEL 必须在 Lua 里原子完成。
 * 若先 GET 再 DEL，中间可能被并发消费两次。
 */
public final class RedisOneTimeTokenStore implements OneTimeTokenStore {

    private static final String CONSUME_LUA =
            "local v = redis.call('GET', KEYS[1])\n"
            + "if not v then return nil end\n"
            + "local sep = string.find(v, '|', 1, true)\n"
            + "if not sep then return nil end\n"
            + "if string.sub(v, 1, sep - 1) ~= ARGV[1] then return nil end\n"
            + "redis.call('DEL', KEYS[1])\n"
            + "return string.sub(v, sep + 1)";

    private static final DefaultRedisScript<String> CONSUME =
            new DefaultRedisScript<>(CONSUME_LUA, String.class);

    private final StringRedisTemplate redis;
    private final IdGenerator ids;
    private final String prefix;

    public RedisOneTimeTokenStore(StringRedisTemplate redis, IdGenerator ids, String prefix) {
        this.redis = redis;
        this.ids = ids;
        this.prefix = prefix;
    }

    @Override
    public String issue(String purpose, String subject, Duration ttl) {
        String jti = ids.next();
        redis.opsForValue().set(key(jti), purpose + "|" + subject, ttl);
        return jti;
    }

    @Override
    public Optional<String> consume(String purpose, String jti) {
        return Optional.ofNullable(redis.execute(CONSUME, List.of(key(jti)), purpose));
    }

    private String key(String jti) {
        return prefix + "otl:" + jti;
    }
}
