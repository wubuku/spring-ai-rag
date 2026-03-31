package com.springairag.demo.medical;

import com.springairag.api.service.PromptCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 医疗问诊 Prompt 定制器
 *
 * <p>实现 PromptCustomizer 接口，为医疗领域的用户消息添加上下文：
 * <ul>
 *   <li>校验问题是否属于医疗领域</li>
 *   <li>为用户消息添加领域前缀，引导模型聚焦医学分析</li>
 * </ul>
 *
 * <p>Starter 自动发现 @Component 注册的 PromptCustomizer，
 * 通过 PromptCustomizerChain 按 order 排序后链式调用。
 */
@Component
public class MedicalPromptCustomizer implements PromptCustomizer {

    @Override
    public String customizeUserMessage(String originalUserMessage, Map<String, Object> metadata) {
        // 如果 metadata 中有 domainId=medical，添加问诊引导前缀
        Object domainId = metadata.get("domainId");
        if ("medical".equals(domainId)) {
            return """
                    [医疗问诊模式]
                    用户健康咨询：%s
                    
                    请从症状描述、可能原因、建议措施三个角度回答。
                    """.formatted(originalUserMessage);
        }
        return originalUserMessage;
    }

    @Override
    public int getOrder() {
        return 100; // 较低优先级，先经过其他定制器
    }
}
