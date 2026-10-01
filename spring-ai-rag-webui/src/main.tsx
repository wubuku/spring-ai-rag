import { createRoot } from 'react-dom/client';
// Import order is part of the design-system contract: generated design tokens
// must land before the global base layer so element defaults can reference them.
import './styles/tokens.css';
import './styles/global.css';
import './i18n'; // i18n configuration (react-i18next + language detector)
import App from './App.tsx';
import { clearLegacyCredentialStorage } from './auth/credentialStore';

// StrictMode disabled for debugging SSE streaming issues
clearLegacyCredentialStorage();
createRoot(document.getElementById('root')!).render(<App />);
