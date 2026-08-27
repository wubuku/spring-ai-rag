package com.springairag.core.embeddingjob;

/**
 * 表示持久化 embedding job 表中可能出现了可消费任务。
 *
 * <p>事件只负责低延迟唤醒，不承载任务正文或可靠状态；数据库任务表仍是唯一事实来源。
 */
public record EmbeddingJobsAvailableEvent() {
}
