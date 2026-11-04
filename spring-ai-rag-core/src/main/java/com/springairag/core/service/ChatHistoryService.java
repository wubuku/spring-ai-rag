package com.springairag.core.service;

import com.springairag.api.dto.ChatHistoryResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.repository.RagChatHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天历史的读取与清理入口。
 *
 * <p>把控制器对历史 repository 的直接访问收敛到 service 层，
 * 便于“controller 不得依赖 repository”的架构门禁长期生效。</p>
 */
@Service
public class ChatHistoryService {

    private final RagChatHistoryRepository historyRepository;

    public ChatHistoryService(RagChatHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Transactional(readOnly = true)
    public List<ChatHistoryResponse> findHistory(ChatPrincipal principal, String sessionId, int limit) {
        return historyRepository.findByPrincipalAndSession(principal, sessionId, limit);
    }

    @Transactional
    public int clearSession(ChatPrincipal principal, String sessionId) {
        return historyRepository.deleteByPrincipalAndSession(principal, sessionId);
    }

    @Transactional
    public int clearSession(String sessionId) {
        return historyRepository.deleteBySessionId(sessionId);
    }
}
