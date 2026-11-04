package com.springairag.core.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The classifier decides whether clients are told to retry. If it degrades
 * everything to FAILURE, pool exhaustion goes back to being a 500.
 */
class DatabaseFailureClassifierTest {

    @Test
    void poolExhaustion_isUnavailable() {
        CannotGetJdbcConnectionException exception = new CannotGetJdbcConnectionException(
                "Connection is not available, request timed out after 1000ms",
                new SQLTransientConnectionException("pool exhausted"));

        assertEquals(DatabaseFailureClassifier.Kind.UNAVAILABLE,
                DatabaseFailureClassifier.classify(exception));
    }

    @Test
    void queryTimeout_isTimeoutEvenWhenWrapped() {
        QueryTimeoutException exception = new QueryTimeoutException(
                "canceling statement due to statement timeout",
                new SQLException("canceling statement due to statement timeout", "57014"));

        assertEquals(DatabaseFailureClassifier.Kind.TIMEOUT,
                DatabaseFailureClassifier.classify(exception));
    }

    @Test
    void postgresqlCancelState_isTimeout() {
        DataAccessException exception = new DataAccessException(
                "query failed", new SQLException("canceling statement due to statement timeout", "57014")) {
        };

        assertEquals(DatabaseFailureClassifier.Kind.TIMEOUT,
                DatabaseFailureClassifier.classify(exception));
    }

    @Test
    void connectionSqlStateClass_isUnavailable() {
        DataAccessException exception = new DataAccessException(
                "connection broken", new SQLException("connection reset", "08006")) {
        };

        assertEquals(DatabaseFailureClassifier.Kind.UNAVAILABLE,
                DatabaseFailureClassifier.classify(exception));
    }

    @Test
    void plainDataAccessExceptionWithoutConnectionCause_staysFailure() {
        DataAccessException exception = new DataAccessException("connection timeout") {
        };

        assertEquals(DatabaseFailureClassifier.Kind.FAILURE,
                DatabaseFailureClassifier.classify(exception));
    }
}
