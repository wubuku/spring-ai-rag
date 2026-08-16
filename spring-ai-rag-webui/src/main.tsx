import { createRoot } from 'react-dom/client';
import './styles/global.css';
import './i18n'; // i18n configuration (react-i18next + language detector)
import App from './App.tsx';
import { clearLegacyCredentialStorage } from './auth/credentialStore';

// StrictMode disabled for debugging SSE streaming issues
clearLegacyCredentialStorage();
createRoot(document.getElementById('root')!).render(<App />);
