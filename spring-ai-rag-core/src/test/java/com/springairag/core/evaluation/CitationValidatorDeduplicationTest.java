package com.springairag.core.evaluation;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.CitationValidation;
import com.springairag.api.enums.ChatMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用校验的去重与空输入边界（Batch 764）。
 *
 * <p>既有测试用一个方法走遍了 VALID / MISSING / INVALID / PARTIAL / NOT_APPLICABLE
 * 五种状态，但每个状态只走了一���典型输入，缺三类会直接影响对外报数的边界：
 *
 * <ol>
 *   <li><strong>重复引用去重</strong>：同一个 {@code [S1]} 出现三次只应计一次。
 *       去重逻辑坏了，{@code citedSourceCount} 就会虚高，前端会显示"3/3 已引用"
 *       而实际只引用了一个来源。</li>
 *   <li><strong>空输入的来源过滤</strong>：sources 里混入 null 元素、null 引用 id
 *       或空白 id 时，必须被忽略而不是算进可用来源或直接崩掉。</li>
 *   <li><strong>零号引用</strong>：{@code [S0]} 在正则上合法但语义上不是有效来源，
 *       应当落进 invalidIds 而不是被当成有效引用。</li>
 * </ol>
 */
class CitationValidatorDeduplicationTest {

    private final CitationValidator validator = new CitationValidator();

    private static ChatSource source(String citationId) {
        ChatSource source = new ChatSource();
        source.setCitationId(citationId);
        return source;
    }

    private CitationValidation validate(ChatMode mode, String answer,
                                         List<ChatSource> sources) {
        return validator.validate(mode, answer, sources);
    }

    // ==================== 重复引用去重 ====================

    @Test
    @DisplayName("同一引用重复出现只计一次")
    void repeatedCitationIsCountedOnce() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE,
                "首段说 [S1]。中段又说 [S1]。结尾再提一次 [S1]。",
                List.of(source("S1")));

        assertEquals(CitationValidation.VALID, result.status());
        assertEquals(1, result.citedSourceCount(),
                () -> "重复引用只应计一次，实际：" + result.citedSourceCount());
        assertEquals(List.of("S1"), result.citedIds());
    }

    @Test
    @DisplayName("多个来源各自重复时按来源去重")
    void eachSourceIsDeduplicatedIndependently() {
        // S3 未被引用不降级状态：校验只关心"引用是否合法"，不要求每个来源都被引到。
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE,
                "[S1] [S1] [S2] [S2] [S2] end",
                List.of(source("S1"), source("S2"), source("S3")));

        assertEquals(CitationValidation.VALID, result.status());
        assertEquals(List.of("S1", "S2"), result.citedIds());
        assertEquals(2, result.citedSourceCount());
        assertEquals(3, result.sourceCount());
    }

    @Test
    @DisplayName("重复的无效引用同样只报一次")
    void repeatedInvalidCitationIsReportedOnce() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE,
                "[S9] [S9] [S9] plus [S1]",
                List.of(source("S1")));

        assertEquals(CitationValidation.PARTIAL, result.status());
        assertEquals(List.of("S9"), result.invalidIds(),
                "同一个无效引用重复三次只应报一次");
    }

    // ==================== 来源列表的空值过滤 ====================

    @Test
    @DisplayName("来源列表为 null 时按无可用来源处理")
    void nullSourcesIsTreatedAsEmpty() {
        CitationValidation result = validate(ChatMode.KNOWLEDGE, "没有引用", null);

        assertEquals(CitationValidation.NOT_APPLICABLE, result.status());
        assertEquals(0, result.sourceCount());
    }

    @Test
    @DisplayName("来源列表含 null 元素时被忽略")
    void nullSourceElementIsIgnored() {
        List<ChatSource> sources = new ArrayList<>();
        sources.add(null);
        sources.add(source("S1"));
        sources.add(null);

        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, "引用 [S1]", sources);

        assertEquals(CitationValidation.VALID, result.status());
        assertEquals(List.of("S1"), result.availableIds(),
                "null 元素不应进入可用来源列表");
        assertEquals(1, result.sourceCount());
    }

    @Test
    @DisplayName("引用 id 为 null 或空白的来源被忽略")
    void blankCitationIdSourcesAreIgnored() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE,
                "引用 [S1]",
                List.of(source(null), source(""), source("   "), source("S1")));

        assertEquals(List.of("S1"), result.availableIds());
        assertEquals(1, result.sourceCount());
    }

    @Test
    @DisplayName("全部来源都不可用且答案无引用时为 NOT_APPLICABLE")
    void allSourcesUnusableWithoutCitations() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, "纯文本回答", List.of(source(null), source("")));

        assertEquals(CitationValidation.NOT_APPLICABLE, result.status());
    }

    @Test
    @DisplayName("答案为 null 时按无引用处理")
    void nullAnswerIsTreatedAsNoCitation() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, null, List.of(source("S1")));

        assertEquals(CitationValidation.MISSING_CITATION, result.status());
        assertEquals(List.of("S1"), result.availableIds());
        assertTrue(result.citedIds().isEmpty());
    }

    // ==================== 零号与畸形引用 ====================

    @Test
    @DisplayName("零号引用落进无效列表")
    void zeroCitationIsInvalid() {
        // [S0] 在正则上匹配，但没有任何来源叫 S0，不应被当成有效引用。
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, "引用 [S0]", List.of(source("S1")));

        assertEquals(CitationValidation.INVALID_CITATION, result.status());
        assertEquals(List.of("S0"), result.invalidIds());
    }

    @Test
    @DisplayName("非方括号形式不被当作引用")
    void bracketlessReferenceIsIgnored() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, "见 S1 和 [source:1]", List.of(source("S1")));

        assertEquals(CitationValidation.MISSING_CITATION, result.status());
        assertTrue(result.citedIds().isEmpty());
    }

    @Test
    @DisplayName("多位数引用 id 正常解析")
    void multiDigitCitationIdIsParsed() {
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, "引用 [S12] 和 [S3]",
                List.of(source("S12"), source("S3")));

        assertEquals(CitationValidation.VALID, result.status());
        assertEquals(List.of("S12", "S3"), result.citedIds());
    }

    @Test
    @DisplayName("可用来源重复出现时只保留一份")
    void duplicateAvailableIdsAreCollapsed() {
        // 两个来源共用同一个引用 id 是脏数据；报告里的可用来源不应重复计数。
        CitationValidation result = validate(
                ChatMode.KNOWLEDGE, "引用 [S1]",
                List.of(source("S1"), source("S1")));

        assertEquals(List.of("S1"), result.availableIds());
    }

    // ==================== PLAIN 模式短路 ====================

    @Test
    @DisplayName("PLAIN 模式对任何输入都短路为 NOT_APPLICABLE")
    void plainModeShortCircuits() {
        List<List<ChatSource>> inputs = Arrays.asList(
                null,
                Collections.emptyList(),
                Collections.singletonList(source("S1")));
        for (List<ChatSource> sources : inputs) {
            CitationValidation result = validate(ChatMode.PLAIN, "任意 [S9]", sources);
            assertEquals(CitationValidation.NOT_APPLICABLE, result.status());
            assertEquals(0, result.citedSourceCount());
        }
    }

    @Test
    @DisplayName("AGENT 模式参与引用校验")
    void agentModeIsValidated() {
        CitationValidation result = validate(
                ChatMode.AGENT, "引用 [S1]", List.of(source("S1")));

        assertEquals(CitationValidation.VALID, result.status());
    }
}
