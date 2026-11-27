package com.example.baseplate.api.port.out;

import com.example.baseplate.api.model.DispatchEvent;

/**
 * 分单生命周期监听：分配、进池、认领、拒绝、超时、失败都会触发。
 * 实现方不能抛异常影响主流程——事件是旁路，不是事务的一部分。
 */
public interface DispatchLifecycleListener {

    void onEvent(DispatchEvent event);

    default int order() {
        return 100;
    }
}
