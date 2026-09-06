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
