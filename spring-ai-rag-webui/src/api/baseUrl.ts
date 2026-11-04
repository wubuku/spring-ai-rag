/**
 * 所有 WebUI 请求的统一 API 根路径。
 *
 * 独立成模块的原因：首屏只需要这个字符串（错误上报、SSE、上传），
 * 不应该为了一个常量把整个 axios 客户端打进首屏 chunk。
 */
export const BASE_URL = '/api/v1/rag';
