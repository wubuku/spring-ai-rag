package com.springairag.documents.chunk;

/**
 * 文本分块结果。
 *
 * @param text      块内容（已 trim）
 * @param startPos  在原文中的起始位置（字符索引）
 * @param endPos    在原文中的结束位置（字符索引，exclusive）
 */
public record TextChunk(String text, int startPos, int endPos) {
}
