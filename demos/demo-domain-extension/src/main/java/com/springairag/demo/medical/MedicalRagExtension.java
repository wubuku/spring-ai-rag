package com.springairag.demo.medical;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.service.DomainRagExtension;
import org.springframework.stereotype.Component;

/**
 * 医疗问诊领域扩展
 *
 * <p>实现 DomainRagExtension 接口，提供医疗领域的：
 * <ul>
 *   <li>专业的系统提示词模板（含问诊安全规则）</li>
 *   <li>定制的检索配置（高精度模式，侧重召回率）</li>
 *   <li>查询适用性校验（过滤非医疗问题）</li>
 * </ul>
 *
 * <p>Starter 自动扫描 @Component 注册的 DomainRagExtension，
 * 通过 DomainExtensionRegistry 管理，用户可通过 domainId 参数选择。
 */
@Component
public class MedicalRagExtension implements DomainRagExtension {

    @Override
    public String getDomainId() {
        return "medical";
    }

    @Override
    public String getDomainName() {
        return "医疗问诊";
    }

    @Override
    public String getSystemPromptTemplate() {
        return """
                你是一个专业的医疗健康问诊助手。请基于以下医学参考资料回答用户的问题。
                
                【安全规则 — 必须遵守】
                1. 你不是执业医师，所有回答仅供参考，不能替代专业医生的诊断和治疗建议
                2. 对于紧急情况（胸痛、呼吸困难、大出血等），必须立即建议拨打 120 急救电话
                3. 不要开具处方药或具体用药剂量，建议用户咨询专业医生
                4. 如果问题超出你的知识范围，请明确告知用户
                5. 鼓励用户在必要时就医检查
                
                【回答要求】
                1. 基于提供的医学参考资料回答，不要编造信息
                2. 使用通俗易懂的语言，避免过多专业术语
                3. 回答要条理清晰，重点突出
                4. 标注信息来源
                5. 适当提醒用户：如症状持续或加重，请及时就医
                
                参考资料：
                {context}
                """;
    }

    @Override
    public RetrievalConfig getRetrievalConfig() {
        // 医疗领域侧重召回率，宁可多返回也不遗漏
        return RetrievalConfig.builder()
                .maxResults(15)       // 返回更多结果
                .minScore(0.3)       // 降低阈值，确保召回
                .useHybridSearch(true)
                .useRerank(true)
                .vectorWeight(0.5)   // 向量和全文权重均衡
                .fulltextWeight(0.5)
                .build();
    }

    @Override
    public boolean isApplicable(String query) {
        // 医疗领域关键词过滤
        String lower = query.toLowerCase();
        return lower.contains("疼") || lower.contains("痛") || lower.contains("病")
                || lower.contains("药") || lower.contains("症") || lower.contains("医")
                || lower.contains("健康") || lower.contains("检查") || lower.contains("治疗")
                || lower.contains("发烧") || lower.contains("咳嗽") || lower.contains("过敏")
                || lower.contains("症状") || lower.contains("诊断") || lower.contains("手术");
    }

    @Override
    public String postProcessAnswer(String answer) {
        // 追加标准免责声明
        if (!answer.contains("就医") && !answer.contains("医生")) {
            answer += "\n\n---\n⚠️ 以上内容仅供参考，如有不适请及时就医。";
        }
        return answer;
    }
}
