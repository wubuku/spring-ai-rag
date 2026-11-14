package com.springairag.core.tenant;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * WP5：app DataSource 的 session 级 GUC 三件套（实施规划 §5.3.4 / §6 WP5）。
 *
 * <p>三件套：</p>
 * <ol>
 *   <li><b>checkout RESET</b>：借出连接时先 {@code RESET app.current_tenant_id}，
 *       清掉上一位租户的残留；</li>
 *   <li><b>session 级 set_config</b>：{@code SELECT set_config('app.current_tenant_id', ?, false)}，
 *       第三参数 {@code false} 表示 session 级（非事务级），自动提交路径同样有效；</li>
 *   <li><b>归还 RESET</b>：连接归还池前再次 {@code RESET}，避免下家租户继承上家 GUC。
 *       物理连接创建时的兜底由 Hikari {@code connection-init-sql: RESET} 承担。</li>
 * </ol>
 *
 * <p>无 {@link TenantContext} 时不设置 GUC：RLS 返回 0 行（fail-closed），由服务层断言
 * 拒绝无上下文写路径。事务内 {@code set_config(..., true)} 仅作事务内加固，
 * <b>不以 transaction-local 为唯一主防线</b>——本仓存在一批非事务
 * {@code jdbcTemplate.update/batchUpdate} 文件，{@code SET LOCAL} 在自动提交连接上
 * 语句结束即失效，会造成 STRICT 下静默读 0 行。</p>
 *
 * <p>GUC 回读断言：借出连接后回读 {@code current_setting('app.current_tenant_id', true)}，
 * 必须与 {@link TenantContext#tenantId()} 一致；不一致立即关闭连接并抛出
 * {@link IllegalStateException}（fail-closed），把「读 0 行 ≠ 成功」变成可测护栏。</p>
 */
public class TenantGucDataSource implements DataSource {

    /** session 级 GUC 名（与 V80 policy 绑定一致）。 */
    public static final String TENANT_GUC_NAME = "app.current_tenant_id";

    private final DataSource delegate;

    public TenantGucDataSource(DataSource delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate DataSource must not be null");
        }
        this.delegate = delegate;
    }

    public DataSource delegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection raw) throws SQLException {
        try {
            TenantContext context = TenantContext.current();
            if (context != null) {
                resetGuc(raw);
                applySessionGuc(raw, context.tenantId());
                String actual = readBackGuc(raw);
                if (!context.tenantId().equals(actual)) {
                    throw new IllegalStateException(
                            "租户 GUC 回读不一致，拒绝使用该连接：期望 tenant="
                                    + context.tenantId() + "，回读=" + actual
                                    + "；这是连接污染或 RLS 绑定失效，必须 fail-closed");
                }
            } else {
                // 无上下文：不设置 GUC，但也要清掉残留，避免读到他人的数据。
                resetGuc(raw);
            }
            return TenantGucConnection.wrap(this, raw);
        } catch (RuntimeException | SQLException e) {
            closeQuietly(raw);
            throw e;
        }
    }

    /** checkout RESET + session 级 set_config。 */
    private void applySessionGuc(Connection c, String tenantId) throws SQLException {
        try (PreparedStatementHolder holder = new PreparedStatementHolder(c,
                "SELECT set_config('" + TENANT_GUC_NAME + "', ?, false)")) {
            holder.statement().setString(1, tenantId);
            holder.statement().execute();
        }
    }

    /** 清空 GUC（checkout 与归还各一次）。 */
    private void resetGuc(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("RESET " + TENANT_GUC_NAME);
        }
    }

    /**
     * 回读当前 session GUC 值；未设置或已被清空时返回 {@code null}。
     * 可被测试覆写以模拟连接污染。
     */
    protected String readBackGuc(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             var rs = s.executeQuery(
                     "SELECT current_setting('" + TENANT_GUC_NAME + "', true)")) {
            if (!rs.next()) {
                return null;
            }
            String value = rs.getString(1);
            return value == null || value.isEmpty() ? null : value;
        }
    }

    /** 归还连接前 RESET（由包装连接的 close 触发）。 */
    void resetOnReturn(Connection c) {
        try {
            resetGuc(c);
        } catch (SQLException e) {
            // 归还阶段不抛给业务方：连接将被物理丢弃或复用前再 RESET 一次。
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // 关闭失败不掩盖原始异常
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }

    @Override
    public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    /** 轻量持有者，避免每次 set_config 都重复拼接 SQL。 */
    private static final class PreparedStatementHolder implements AutoCloseable {
        private final java.sql.PreparedStatement statement;

        PreparedStatementHolder(Connection c, String sql) throws SQLException {
            this.statement = c.prepareStatement(sql);
        }

        java.sql.PreparedStatement statement() { return statement; }

        @Override
        public void close() throws SQLException {
            statement.close();
        }
    }
}
