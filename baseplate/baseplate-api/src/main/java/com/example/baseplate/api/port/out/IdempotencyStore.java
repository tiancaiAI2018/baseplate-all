package com.example.baseplate.api.port.out;

import java.time.Duration;

/**
 * 写接口幂等。
 *
 * <p>B2 修正（B1 的 Optional 版本无法区分两种空）：重复请求可能遇到「首次请求仍未完成」，
 * 此时不能当成功也不能当失败。用三态结果表达：
 * <ul>
 *   <li>acquired=true：本次抢到占位，继续执行业务；</li>
 *   <li>acquired=false 且 cachedResult=null：重复请求，首次仍在执行；</li>
 *   <li>acquired=false 且 cachedResult!=null：重复请求，返回首次结果快照。</li>
 * </ul>
 */
public interface IdempotencyStore {

    record BeginResult(boolean acquired, String cachedResult) {
        public boolean hasCachedResult() {
            return cachedResult != null;
        }
    }

    BeginResult begin(String tenant, String key, Duration ttl);

    void finish(String tenant, String key, String resultJson);

    /** 业务失败时释放占位，允许客户端重试。 */
    void release(String tenant, String key);
}
