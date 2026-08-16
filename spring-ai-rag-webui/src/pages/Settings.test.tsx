import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
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
    render(<Settings />);
    // Mock i18n returns 'settings.title' key, which contains 'Settings'
    expect(screen.getByText(/settings\.title/i)).toBeInTheDocument();
  });

  it('renders settings tabs without an API key persistence tab', () => {
    render(<Settings />);
    // Mock returns keys: settings.llmProvider, settings.retrieval, settings.cache, language label
    expect(screen.getByRole('button', { name: /settings\.llmProvider/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /settings\.retrieval/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /settings\.cache/i })).toBeInTheDocument();
    expect(screen.queryByText('settings.ragApiKey')).not.toBeInTheDocument();
  });

  it('shows save button disabled when no changes', () => {
    render(<Settings />);
    const saveBtn = screen.getByRole('button', { name: /settings\.save/i });
    expect(saveBtn).toBeInTheDocument();
    expect(saveBtn).toBeDisabled();
  });

  it('allows selecting and persisting a configured runtime model', async () => {
    render(<Settings />);
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
});
