package com.springairag.core.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * WP5：连接归还钩子（实施规划 §5.3.4 三件套第 3 件）。
 *
 * <p>用 JDK 动态代理包装物理连接：调用 {@code close()} 归还池前先
 * {@link TenantGucDataSource#resetOnReturn(Connection)} 清掉 session GUC，
 * 避免下一位租户继承上家上下文。其它方法原样委托。</p>
 */
final class TenantGucConnection implements InvocationHandler {

    private final TenantGucDataSource owner;
    private final Connection delegate;
    private boolean closed;

    private TenantGucConnection(TenantGucDataSource owner, Connection delegate) {
        this.owner = owner;
        this.delegate = delegate;
    }

    static Connection wrap(TenantGucDataSource owner, Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                TenantGucConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantGucConnection(owner, delegate));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("close".equals(name)) {
            if (!closed) {
                closed = true;
                owner.resetOnReturn(delegate);
            }
            return invokeDelegate(method, args);
        }
        if ("isClosed".equals(name) && closed) {
            return Boolean.TRUE;
        }
        if ("unwrap".equals(name)) {
            Class<?> target = (Class<?>) args[0];
            if (target.isInstance(proxy)) {
                return proxy;
            }
            if (target.isInstance(delegate)) {
                return delegate;
            }
        }
        return invokeDelegate(method, args);
    }

    private Object invokeDelegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
