import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { Settings } from './Settings';
import { modelsApi } from '../api/models';

vi.mock('../api/models', () => ({
  modelsApi: {
    list: vi.fn(),
  },
}));

// Mock localStorage for jsdom environment
const localStorageMock = {
  getItem: vi.fn(() => null),
  setItem: vi.fn(() => {}),
  removeItem: vi.fn(() => {}),
  clear: vi.fn(() => {}),
};
Object.defineProperty(window, 'localStorage', { value: localStorageMock });

describe('Settings', () => {
  const renderSettings = () => render(
    <MemoryRouter>
      <Settings />
    </MemoryRouter>,
  );

  beforeEach(() => {
    vi.clearAllMocks();
    localStorageMock.getItem.mockReturnValue(null);
    (modelsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        multiModelEnabled: true,
        defaultProvider: 'minimax',
        defaultModel: 'minimax/MiniMax-M2.7',
        availableProviders: ['minimax', 'openrouter'],
        fallbackChain: ['openrouter/xiaomi/mimo-v2-pro'],
        models: [
          {
            ref: 'minimax/MiniMax-M2.7',
            provider: 'minimax',
            providerName: 'MiniMax',
            modelId: 'MiniMax-M2.7',
            name: 'MiniMax M2.7',
            apiType: 'anthropic-messages',
            available: true,
          },
          {
            ref: 'openrouter/xiaomi/mimo-v2-pro',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'xiaomi/mimo-v2-pro',
            name: 'MiMo V2 Pro',
            apiType: 'openai-completions',
            available: true,
          },
        ],
      },
    });
  });

  it('renders page title', () => {
    renderSettings();
    // Mock i18n returns 'settings.title' key, which contains 'Settings'
    expect(screen.getByText(/settings\.title/i)).toBeInTheDocument();
  });

  it('renders settings tabs without an API key persistence tab', () => {
    renderSettings();
    // Mock returns keys: settings.llmProvider, settings.retrieval, settings.cache, language label
    expect(screen.getByRole('button', { name: /settings\.llmProvider/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /settings\.retrieval/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /settings\.cache/i })).toBeInTheDocument();
    expect(screen.queryByText('settings.ragApiKey')).not.toBeInTheDocument();
  });

  it('shows save button disabled when no changes', () => {
    renderSettings();
    const saveBtn = screen.getByRole('button', { name: /settings\.save/i });
    expect(saveBtn).toBeInTheDocument();
    expect(saveBtn).toBeDisabled();
  });

  it('allows selecting and persisting a configured runtime model', async () => {
    renderSettings();
    const provider = await screen.findByTestId('settings-provider-select');
    const model = screen.getByTestId('settings-model-select');
    await waitFor(() => {
      expect(provider).toBeEnabled();
      expect(model).toBeEnabled();
    });

    fireEvent.change(provider, { target: { value: 'openrouter' } });
    fireEvent.change(model, {
      target: { value: 'openrouter/xiaomi/mimo-v2-pro' },
    });
    const saveBtn = screen.getByRole('button', { name: /settings\.save/i });
    expect(saveBtn).toBeEnabled();
    fireEvent.click(saveBtn);

    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      'rag-selected-model',
      'openrouter/xiaomi/mimo-v2-pro'
    );
  });

  it('associates retrieval and cache controls with their labels', () => {
    render(
      <MemoryRouter initialEntries={['/settings?tab=retrieval']}>
        <Settings />
      </MemoryRouter>,
    );

    // Labels are rendered through the i18n test mock, which returns the key.
    for (const label of [
      'settings.vectorWeight',
      'settings.fulltextWeight',
      'settings.topK',
      'settings.rerankTopK',
    ]) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
  });
});

describe('Settings persistence and model fallback branches', () => {
  const renderSettings = (search = '') => render(
    <MemoryRouter initialEntries={[`/settings${search}`]}>
      <Settings />
    </MemoryRouter>,
  );

  beforeEach(() => {
    vi.clearAllMocks();
    localStorageMock.getItem.mockReturnValue(null);
    (modelsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        multiModelEnabled: true,
        defaultProvider: 'minimax',
        defaultModel: 'minimax/MiniMax-M2.7',
        availableProviders: ['minimax', 'openrouter'],
        fallbackChain: [],
        models: [
          {
            ref: 'minimax/MiniMax-M2.7',
            provider: 'minimax',
            providerName: 'MiniMax',
            modelId: 'MiniMax-M2.7',
            name: 'MiniMax M2.7',
            apiType: 'anthropic-messages',
            available: true,
          },
          {
            ref: 'openrouter/xiaomi/mimo-v2-pro',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'xiaomi/mimo-v2-pro',
            name: 'MiMo V2 Pro',
            apiType: 'openai-completions',
            available: true,
          },
        ],
      },
    });
  });

  it('restores retrieval and cache settings from stored JSON', async () => {
    localStorageMock.getItem.mockImplementation((key: string) => {
      if (key === 'user_settings') {
        return JSON.stringify({
          vectorWeight: 0.9,
          fulltextWeight: 0.1,
          topK: 25,
          rerankTopK: 7,
          enabled: false,
          ttlMinutes: 5,
          maxSize: 42,
          llmModel: 'openrouter/xiaomi/mimo-v2-pro',
        });
      }
      if (key === 'rag-selected-model') {
        return 'openrouter/xiaomi/mimo-v2-pro';
      }
      return null;
    });

    renderSettings('?tab=retrieval');

    // range 输入的 value 以字符串呈现。
    expect(await screen.findByLabelText('settings.vectorWeight'))
      .toHaveValue('0.9');
    expect(screen.getByLabelText('settings.topK')).toHaveValue(25);

    fireEvent.click(screen.getByRole('button', { name: 'settings.cache' }));
    expect(screen.getByLabelText('settings.ttlMinutes')).toHaveValue(5);
    expect(screen.getByLabelText('settings.maxSize')).toHaveValue(42);
  });

  it('falls back to defaults when stored settings are corrupt', async () => {
    localStorageMock.getItem.mockImplementation((key: string) =>
      key === 'user_settings' ? '{corrupt' : null);

    renderSettings('?tab=retrieval');

    expect(await screen.findByLabelText('settings.vectorWeight'))
      .toHaveValue('0.7');
    expect(screen.getByLabelText('settings.topK')).toHaveValue(10);
  });

  it('prefers the stored model over the default model', async () => {
    localStorageMock.getItem.mockImplementation((key: string) =>
      key === 'rag-selected-model' ? 'openrouter/xiaomi/mimo-v2-pro' : null);
    renderSettings();
    const provider = await screen.findByTestId('settings-provider-select');
    const model = screen.getByTestId('settings-model-select');
    await waitFor(() => {
      expect(provider).toHaveValue('openrouter');
      expect(model).toHaveValue('openrouter/xiaomi/mimo-v2-pro');
    });
  });

  it('falls back to the default model when the stored model is missing', async () => {
    localStorageMock.getItem.mockImplementation((key: string) =>
      key === 'rag-selected-model' ? 'ghost/model' : null);
    renderSettings();
    const provider = await screen.findByTestId('settings-provider-select');
    const model = screen.getByTestId('settings-model-select');
    await waitFor(() => {
      expect(provider).toHaveValue('minimax');
      expect(model).toHaveValue('minimax/MiniMax-M2.7');
    });
  });

  it('clears the previous saved-indicator timer on a second save', async () => {
    const user = userEvent.setup();
    renderSettings('?tab=retrieval');

    const vectorWeight = await screen.findByLabelText('settings.vectorWeight');
    // 两次保存：第二次保存会 clearTimeout 第一次的指示器定时器。
    fireEvent.change(vectorWeight, { target: { value: '0.8' } });
    const save = screen.getByRole('button', { name: /settings\.save/i });
    await waitFor(() => expect(save).toBeEnabled());
    await user.click(save);
    await waitFor(() =>
      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'user_settings',
        expect.any(String),
      ),
    );

    fireEvent.change(vectorWeight, { target: { value: '0.6' } });
    await waitFor(() => expect(save).toBeEnabled());
    await user.click(save);

    await waitFor(() => {
      const calls = localStorageMock.setItem.mock.calls.filter(
        call => call[0] === 'user_settings',
      );
      expect(calls.length).toBeGreaterThanOrEqual(2);
      const last = JSON.parse(calls.at(-1)![1] as string);
      expect(last.vectorWeight).toBe(0.6);
    });
  });

  it('persists the language preference through the language buttons', async () => {
    const user = userEvent.setup();
    renderSettings('?tab=language');

    // 点击 English：changeLanguage('en') 并写入 localStorage。
    await user.click(screen.getByRole('button', { name: /English/ }));

    await waitFor(() => expect(localStorageMock.setItem).toHaveBeenCalledWith(
      'language',
      'en',
    ));
  });

  it('updates the fulltext weight slider through its onChange', async () => {
    renderSettings('?tab=retrieval');

    const slider = await screen.findByLabelText('settings.fulltextWeight');
    fireEvent.change(slider, { target: { value: '0.4' } });

    expect(slider).toHaveValue('0.4');
  });

  it('resets the cache max size to the default when the input is cleared', () => {
    renderSettings();
    fireEvent.click(screen.getByRole('button', { name: 'settings.cache' }));
    const input = screen.getByLabelText('settings.maxSize') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '5555' } });
    expect(input.value).toBe('5555');

    // parseInt('') 为 NaN → 状态回退默认值 1000。
    fireEvent.change(input, { target: { value: '' } });
    expect(input.value).toBe('1000');
  });

  it('persists retrieval and cache changes through handleSave', async () => {
    const user = userEvent.setup();
    renderSettings('?tab=retrieval');

    const vectorWeight = await screen.findByLabelText('settings.vectorWeight');
    fireEvent.change(vectorWeight, { target: { value: '0.8' } });
    const topK = screen.getByLabelText('settings.topK');
    fireEvent.change(topK, { target: { value: '30' } });

    fireEvent.click(screen.getByRole('button', { name: 'settings.cache' }));
    const ttl = screen.getByLabelText('settings.ttlMinutes');
    fireEvent.change(ttl, { target: { value: '15' } });

    const save = screen.getByRole('button', { name: /settings\.save/i });
    await waitFor(() => expect(save).toBeEnabled());
    await user.click(save);

    await waitFor(() => {
      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'user_settings',
        expect.stringContaining('"vectorWeight":0.8'),
      );
    });
    const stored = JSON.parse(
      localStorageMock.setItem.mock.calls
        .find(call => call[0] === 'user_settings')![1] as string,
    );
    expect(stored).toMatchObject({
      vectorWeight: 0.8,
      topK: 30,
      ttlMinutes: 15,
    });
  });
});

describe('Settings model loading, tabs and numeric fallbacks', () => {
  const renderSettings = () => render(
    <MemoryRouter>
      <Settings />
    </MemoryRouter>,
  );

  beforeEach(() => {
    vi.clearAllMocks();
    localStorageMock.getItem.mockReturnValue(null);
    (modelsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        multiModelEnabled: true,
        defaultProvider: 'minimax',
        defaultModel: 'minimax/MiniMax-M2.7',
        availableProviders: ['minimax', 'openrouter'],
        fallbackChain: [],
        models: [
          {
            ref: 'minimax/MiniMax-M2.7',
            provider: 'minimax',
            providerName: 'MiniMax',
            modelId: 'MiniMax-M2.7',
            name: 'MiniMax M2.7',
            apiType: 'anthropic-messages',
            available: true,
          },
          {
            ref: 'openrouter/xiaomi/mimo-v2-pro',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'xiaomi/mimo-v2-pro',
            name: 'MiMo V2 Pro',
            apiType: 'openai-completions',
            available: true,
          },
        ],
      },
    });
  });

  it('filters unavailable models but keeps the default selection', async () => {
    (modelsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        multiModelEnabled: true,
        defaultProvider: 'minimax',
        defaultModel: 'minimax/MiniMax-M2.7',
        availableProviders: ['minimax'],
        fallbackChain: [],
        models: [
          {
            ref: 'minimax/MiniMax-M2.7',
            provider: 'minimax',
            providerName: 'MiniMax',
            modelId: 'MiniMax-M2.7',
            name: 'MiniMax M2.7',
            apiType: 'anthropic-messages',
            available: true,
          },
          {
            ref: 'openrouter/machine/unavailable',
            provider: 'openrouter',
            providerName: 'OpenRouter',
            modelId: 'machine/unavailable',
            name: 'Unavailable Model',
            apiType: 'openai-completions',
            available: false,
          },
        ],
      },
    });

    renderSettings();

    const modelSelect = await screen.findByTestId('settings-model-select') as HTMLSelectElement;
    await waitFor(() => {
      expect(modelSelect.value).toBe('minimax/MiniMax-M2.7');
    });
    // 模型下拉只包含 available 的模型。
    expect(
      Array.from(modelSelect.options).map(option => option.value),
    ).toEqual(['minimax/MiniMax-M2.7']);
    expect(screen.getByText('settings.availableProvider')).toBeInTheDocument();
  });

  it('flags the provider hint when the model list fails to load', async () => {
    (modelsApi.list as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('boom'),
    );

    renderSettings();

    expect(
      await screen.findByText('settings.modelsLoadError'),
    ).toBeInTheDocument();
  });

  it('switches language from the language tab and persists the choice', async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(screen.getByRole('button', { name: 'Language' }));
    await user.click(screen.getByRole('button', { name: /中文/ }));

    expect(localStorageMock.setItem).toHaveBeenCalledWith('language', 'zh-CN');
  });

  it('edits retrieval weights and applies integer fallbacks', async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(screen.getByRole('button', { name: /settings\.retrieval/i }));

    // 语义权重与全文权重两个滑块，按文档顺序取第二个（fulltextWeight）。
    const sliders = screen.getAllByRole('slider') as HTMLInputElement[];
    const slider = sliders[sliders.length - 1];
    fireEvent.change(slider, { target: { value: '0.3' } });
    expect(screen.getByText('0.3')).toBeInTheDocument();

    const topK = screen.getByLabelText('settings.topK') as HTMLInputElement;
    fireEvent.change(topK, { target: { value: '25' } });
    expect(topK.value).toBe('25');
    fireEvent.change(topK, { target: { value: '' } });
    expect(topK.value).toBe('10');

    const rerankTopK = screen.getByLabelText('settings.rerankTopK') as HTMLInputElement;
    fireEvent.change(rerankTopK, { target: { value: '12' } });
    expect(rerankTopK.value).toBe('12');
    fireEvent.change(rerankTopK, { target: { value: '' } });
    expect(rerankTopK.value).toBe('5');
  });

  it('toggles cache settings and applies fallback defaults', async () => {
    const user = userEvent.setup();
    renderSettings();

    await user.click(screen.getByRole('button', { name: /settings\.cache/i }));

    const checkbox = screen.getByRole('checkbox') as HTMLInputElement;
    expect(checkbox).toBeChecked();
    const ttl = screen.getByLabelText('settings.ttlMinutes') as HTMLInputElement;
    const maxSize = screen.getByLabelText('settings.maxSize') as HTMLInputElement;
    expect(ttl).toBeEnabled();
    expect(maxSize).toBeEnabled();

    // 关闭缓存后数值输入禁用。
    await user.click(checkbox);
    expect(ttl).toBeDisabled();
    expect(maxSize).toBeDisabled();

    // 重新开启并清空输入，回退到默认值。
    await user.click(checkbox);
    fireEvent.change(ttl, { target: { value: '' } });
    expect(ttl.value).toBe('60');
    fireEvent.change(maxSize, { target: { value: '' } });
    expect(maxSize.value).toBe('1000');
  });
});
