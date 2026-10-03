import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TOP_LEVEL_ROUTES,
  clearWorkspaceState,
  readWorkspaceState,
  rememberRoute,
  rememberedRoute,
  removeWorkspaceState,
  topLevelRoute,
  writeWorkspaceState,
} from './workspaceState';

const STORAGE_PREFIX = 'spring-ai-rag:webui:v1:';
const acceptAnything = (): _ is object => true;

/**
 * 凭据样本只取 12 个字符的尾段：拦截正则是 `sk-[a-z0-9_-]{12,}`，
 * 仓库的密钥扫描门禁是 `sk-[A-Za-z0-9_-]{20,}`。短样本既能验证更严的
 * 12 字符门槛，又不会让这条新增行被判成真密钥。别把它"补长"回 20 位。
 */
const SK_LIKE = 'sk-abcdefghijkl';

describe('workspaceState', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('remembers legal state for every top-level route', () => {
    const candidates = new Map([
      ['/dashboard', '/dashboard?view=operations'],
      ['/documents', '/documents?collectionKey=manual&page=2'],
      ['/collections', '/collections?sort=name'],
      ['/chat', '/chat/session-42?mode=AGENT'],
      ['/search', '/search?query=manual&hybrid=true'],
      ['/metrics', '/metrics?from=2026-08-01'],
      ['/evaluation', '/evaluation?tab=suites'],
      ['/embeddings', '/embeddings?status=FAILED'],
      ['/alerts', '/alerts?tab=notification-deliveries'],
      ['/abtest', '/abtest/42'],
      ['/api-keys', '/api-keys?filter=active'],
      ['/files', '/files?path=batch-1%2F&q=manual'],
      ['/settings', '/settings?tab=cache'],
    ]);

    for (const route of TOP_LEVEL_ROUTES) {
      const candidate = candidates.get(route)!;
      const url = new URL(candidate, window.location.origin);
      rememberRoute(url.pathname, url.search);
      expect(rememberedRoute(route)).toBe(candidate);
    }
  });

  it('clears only project workspace state', () => {
    writeWorkspaceState('draft', { value: 'safe' });
    window.sessionStorage.setItem('unrelated', 'keep');

    clearWorkspaceState();

    expect(readWorkspaceState('draft', acceptAnything)).toBeNull();
    expect(window.sessionStorage.getItem('unrelated')).toBe('keep');
  });

  describe('凭据拦截', () => {
    // 草稿键存的是用户原样输入的搜索词，所以这四种形态都会真的出现在线上。
    it.each([
      ['裸 sk- 密钥', { query: SK_LIKE }],
      ['Bearer 授权头', { query: 'Authorization: Bearer abc.def.ghi' }],
      ['x-api-key 头', { query: 'x-api-key: abc123' }],
      ['api_key 赋值', { query: 'api_key=abc123' }],
      ['api-key 赋值', { query: 'api-key: abc123' }],
    ])('拒绝落盘：%s', (_label, value) => {
      expect(writeWorkspaceState('draft', value)).toBe('looks-like-credential');
      expect(readWorkspaceState('draft', acceptAnything)).toBeNull();
    });

    it('放行不含凭据的正常搜索词，哪怕它提到 api key 这个词', () => {
      // 误报方向：用户在问怎么配 API Key 的时候，草稿必须存得下来。
      // 正则要求赋值符，所以"api key 怎么配置"这种问句不该命中。
      const draft = { query: 'api key 怎么配置' };
      expect(writeWorkspaceState('draft', draft)).toBe('ok');
      expect(readWorkspaceState('draft', acceptAnything)).toEqual(draft);
    });

    it('放行中文、表情与长查询这类高频真实输入', () => {
      const draft = { query: '第 3 季度报表 📊 蓝色手机壳' };
      expect(writeWorkspaceState('draft', draft)).toBe('ok');
      expect(readWorkspaceState('draft', acceptAnything)).toEqual(draft);
    });

    it('写入被拒时连带清掉同键的旧值', () => {
      // 旧值可能本身就是上一次没拦住的凭据；只拒新值却留着旧值等于没拦。
      expect(writeWorkspaceState('draft', { query: 'safe' })).toBe('ok');
      expect(writeWorkspaceState('draft', { query: SK_LIKE })).toBe('looks-like-credential');
      expect(window.sessionStorage.getItem(`${STORAGE_PREFIX}draft`)).toBeNull();
    });
  });

  describe('按字节计的上限', () => {
    it('用 UTF-8 字节数而不是字符数判断', () => {
      // 一个汉字是 3 字节：3000 个字是 9000 字节，超过 8KB 上限；
      // 若按字符数算，3000 < 8192 就会被放行。
      expect(writeWorkspaceState('draft', { query: '字'.repeat(3_000) })).toBe('too-large');
      expect(writeWorkspaceState('draft', { query: '字'.repeat(2_000) })).toBe('ok');
    });

    it('读到的值超过上限时清理并当作没有', () => {
      window.sessionStorage.setItem(
        `${STORAGE_PREFIX}draft`,
        JSON.stringify({ query: 'x'.repeat(9 * 1024) }),
      );
      expect(readWorkspaceState('draft', acceptAnything)).toBeNull();
      expect(window.sessionStorage.getItem(`${STORAGE_PREFIX}draft`)).toBeNull();
    });

    it('接受调用方自定义的上限', () => {
      // 默认 8KB 装不下，给 routes 用的 16KB 装得下。
      const large = { value: 'x'.repeat(9 * 1024) };
      expect(writeWorkspaceState('draft', large)).toBe('too-large');
      expect(writeWorkspaceState('draft', large, 16 * 1024)).toBe('ok');
    });
  });

  describe('损坏数据降级', () => {
    it('JSON 解析失败时清理并返回 null', () => {
      window.sessionStorage.setItem(`${STORAGE_PREFIX}draft`, '{not json');
      expect(readWorkspaceState('draft', acceptAnything)).toBeNull();
      expect(window.sessionStorage.getItem(`${STORAGE_PREFIX}draft`)).toBeNull();
    });

    it('schema 校验不通过时清理并返回 null', () => {
      // sessionStorage 是同源可写的，别的标签页或脚本能塞进任意内容。
      window.sessionStorage.setItem(`${STORAGE_PREFIX}draft`, JSON.stringify({ width: 'wide' }));
      expect(readWorkspaceState('draft', (value): value is { width: number } => (
        typeof (value as { width?: unknown })?.width === 'number'
      ))).toBeNull();
      expect(window.sessionStorage.getItem(`${STORAGE_PREFIX}draft`)).toBeNull();
    });

    it('路由记忆被写坏时降级回路由本身', () => {
      window.sessionStorage.setItem(`${STORAGE_PREFIX}routes`, '{broken');
      expect(rememberedRoute('/search')).toBe('/search');
    });

    it('路由记忆里的路径不再合法时降级', () => {
      // 存储是可写的：一次被改坏，之后每次进这个页面都跳不对地方。
      window.sessionStorage.setItem(
        `${STORAGE_PREFIX}routes`,
        JSON.stringify({ search: '/files/private/secret' }),
      );
      expect(rememberedRoute('/search')).toBe('/search');
    });
  });

  describe('存储不可用时降级', () => {
    const denyStorage = () => {
      vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
        throw new Error('storage denied');
      });
    };

    it('存储不可用时读返回 null、写报告 storage-unavailable', () => {
      denyStorage();
      expect(readWorkspaceState('draft', acceptAnything)).toBeNull();
      expect(writeWorkspaceState('draft', { value: 'safe' })).toBe('storage-unavailable');
    });

    it('清理类操作不抛异常', () => {
      // 登出路径一定会调 clearWorkspaceState，隐私模式下不能让整页崩掉。
      denyStorage();
      expect(() => removeWorkspaceState('draft')).not.toThrow();
      expect(() => clearWorkspaceState()).not.toThrow();
      rememberRoute('/search', '?q=manual');
    });
  });

  describe('路由记忆的判定边界', () => {
    it.each([
      ['/', null],
      ['/unknown', null],
      ['/settings-advanced', null],
      ['/documents', '/documents'],
      ['/documents/', '/documents'],
      ['/documents/anything/deep', '/documents'],
    ])('topLevelRoute(%s) → %s', (pathname, expected) => {
      expect(topLevelRoute(pathname)).toBe(expected);
    });

    it('不记忆非顶级路由', () => {
      rememberRoute('/unknown', '?q=manual');
      rememberRoute('/', '');
      expect(window.sessionStorage.getItem(`${STORAGE_PREFIX}routes`)).toBeNull();
    });

    it('search 超过 2048 字符时不记忆', () => {
      rememberRoute('/search', `?q=${'x'.repeat(2_100)}`);
      expect(window.sessionStorage.getItem(`${STORAGE_PREFIX}routes`)).toBeNull();

      rememberRoute('/search', `?q=${'x'.repeat(2_000)}`);
      expect(rememberedRoute('/search')).toBe(`/search?q=${'x'.repeat(2_000)}`);
    });

    it('非法深路由不记忆，合法深路由记忆', () => {
      rememberRoute('/files/private/path', '?q=secret');
      expect(rememberedRoute('/files')).toBe('/files');

      // /chat 只接受单段会话 id，/abtest 只接受纯数字。
      rememberRoute('/chat/a/b', '');
      expect(rememberedRoute('/chat')).toBe('/chat');
      rememberRoute('/abtest/not-a-number', '');
      expect(rememberedRoute('/abtest')).toBe('/abtest');
      rememberRoute('/abtest/42', '');
      expect(rememberedRoute('/abtest')).toBe('/abtest/42');
    });
  });

  it('removeWorkspaceState 只删指定键', () => {
    writeWorkspaceState('search-draft', { query: 'a' });
    writeWorkspaceState('chat-draft', { text: 'b' });
    window.sessionStorage.setItem('unrelated', 'keep');

    removeWorkspaceState('search-draft');

    expect(readWorkspaceState('search-draft', acceptAnything)).toBeNull();
    expect(readWorkspaceState('chat-draft', acceptAnything)).toEqual({ text: 'b' });
    expect(window.sessionStorage.getItem('unrelated')).toBe('keep');
  });
});
