package com.springairag.core.evaluation;

import com.springairag.api.enums.ChatMode;
import com.springairag.core.chat.ChatExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationDiagnosticCaptureTest {
    private ChatExecutionResult result(String answer) {
        return new ChatExecutionResult(answer, "session", null, null, "model", ChatMode.KNOWLEDGE,
                List.of(), Map.of(), null, List.of(), Map.of());
    }

    @Test
    void disabledOutsideScopeAndAlwaysCleared() {
        EvaluationDiagnosticCapture.prompt(new Prompt("not captured"));
        try (var capture = EvaluationDiagnosticCapture.open()) {
            assertFalse((Boolean) capture.snapshot().get("complete"));
            EvaluationDiagnosticCapture.prompt(new Prompt("context"));
            EvaluationDiagnosticCapture.result(result("零条"));
            EvaluationDiagnosticCapture.modelAnswer("原始零条", null);
            EvaluationDiagnosticCapture.evidence(List.of(new org.springframework.ai.document.Document("实际输入证据")));
            assertTrue((Boolean) capture.snapshot().get("complete"));
            assertEquals("零条", capture.snapshot().get("answer"));
            assertEquals("原始零条", capture.snapshot().get("modelAnswer"));
            assertThrows(IllegalStateException.class, EvaluationDiagnosticCapture::open);
        }
        try (var capture = EvaluationDiagnosticCapture.open()) {
            assertFalse(capture.snapshot().containsKey("answer"));
        }
    }

    @Test
    void redactionAndTruncationAreExplicitAndCannotClaimComplete() {
        try (var capture = EvaluationDiagnosticCapture.open()) {
            EvaluationDiagnosticCapture.prompt(new Prompt("password=super-secret-value"));
            EvaluationDiagnosticCapture.result(result("x".repeat(40000)));
            var snapshot = capture.snapshot();
            assertEquals(true, snapshot.get("redacted"));
            assertEquals(true, snapshot.get("truncated"));
            assertEquals(false, snapshot.get("complete"));
            assertFalse(snapshot.toString().contains("super-secret-value"));
            assertEquals(32768, ((String) snapshot.get("answer")).length());
        }
    }

    @Test
    void scopesDoNotLeakAcrossThreads() throws Exception {
        try (var capture = EvaluationDiagnosticCapture.open()) {
            var thread = new Thread(() -> EvaluationDiagnosticCapture.prompt(new Prompt("other thread")));
            thread.start();
            thread.join();
            assertFalse(capture.snapshot().containsKey("lastInvocationMessages"));
        }
    }
}
