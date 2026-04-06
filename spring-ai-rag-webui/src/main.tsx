import { createRoot } from 'react-dom/client';
import './index.css';
import './i18n'; // i18n configuration (react-i18next + language detector)
import App from './App.tsx';

// StrictMode disabled for debugging SSE streaming issues
createRoot(document.getElementById('root')!).render(<App />);
