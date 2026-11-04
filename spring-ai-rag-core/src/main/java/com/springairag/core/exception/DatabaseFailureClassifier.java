package com.springairag.core.exception;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;

/**
 * Distinguishes "the database is temporarily not reachable" from "this query
 * failed".
 *
 * <p>Both arrive at {@code GlobalExceptionHandler} as a
 * {@link org.springframework.dao.DataAccessException}, so before this class
 * existed both were answered {@code 500 DATABASE_ERROR}. That is wrong in a way
 * clients act on: 500 means "do not retry, the server is broken", while pool
 * exhaustion and a database that is restarting are precisely retryable. The
 * distinction has to come from the exception chain, because the message text
 * alone is not a contract.</p>
 *
 * <p>Deliberately narrow: an unrecognised failure stays {@link Kind#FAILURE}.
 * Text matching is used only for PostgreSQL SQLStates that pgjdbc reliably
 * reports, never for arbitrary exception messages.</p>
 */
public final class DatabaseFailureClassifier {

    /** What the failure means for the caller. */
    public enum Kind {
        /** Retryable capacity or connectivity condition. */
        UNAVAILABLE,
        /** The statement was cancelled by a bounded timeout. */
        TIMEOUT,
        /** Everything else: a real query/data failure. */
        FAILURE
    }

    /** PostgreSQL SQLState class 08: connection exception. */
    private static final String CONNECTION_SQLSTATE_PREFIX = "08";

    private DatabaseFailureClassifier() {
    }

    public static Kind classify(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 16) {
            // Timeout before availability: SQLTimeoutException extends
            // SQLTransientException, so checking the other branch first would
            // report a bounded query timeout as "database down" and invite an
            // immediate retry of a query that is already too slow.
            if (current instanceof QueryTimeoutException
                    || current instanceof SQLTimeoutException) {
                return Kind.TIMEOUT;
            }
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof SQLTransientConnectionException
                    || current instanceof SQLRecoverableException) {
                // HikariCP reports pool exhaustion as SQLTransientConnectionException
                // with the message "Connection is not available, request timed
                // out after ..." — availability, not a slow statement.
                return Kind.UNAVAILABLE;
            }
            if (current instanceof SQLNonTransientConnectionException) {
                // A non-transient connection failure (bad credentials, unknown
                // database) is still a dependency that is not answering; it is
                // retryable only after the configuration is fixed, but the
                // client must retry rather than treat the request as invalid.
                return Kind.UNAVAILABLE;
            }
            if (current instanceof DataAccessResourceFailureException) {
                // Spring's "could not get a JDBC connection" family.
                return Kind.UNAVAILABLE;
            }
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SocketTimeoutException) {
                return Kind.UNAVAILABLE;
            }
            if (current instanceof SQLException sqlException) {
                Kind fromSqlState = classifySqlState(sqlException);
                if (fromSqlState != null) {
                    return fromSqlState;
                }
            }
            current = current.getCause();
        }
        return Kind.FAILURE;
    }

    /**
     * @return a classification for states that are unambiguously about
     *     connectivity or cancellation, or {@code null} to keep looking
     */
    private static Kind classifySqlState(SQLException exception) {
        String state = exception.getSQLState();
        if (state == null || state.isBlank()) {
            return null;
        }
        if (state.startsWith(CONNECTION_SQLSTATE_PREFIX)) {
            return Kind.UNAVAILABLE;
        }
        // 57014 is `query_canceled`, which is what pgjdbc reports when
        // statement_timeout fires. 57P01..57P03 are server shutdown, crash,
        // and "cannot connect now".
        if ("57014".equals(state)) {
            return Kind.TIMEOUT;
        }
        String normalized = state.toUpperCase(Locale.ROOT);
        if ("57P01".equals(normalized) || "57P02".equals(normalized)
                || "57P03".equals(normalized) || "53300".equals(normalized)) {
            return Kind.UNAVAILABLE;
        }
        return null;
    }
}
