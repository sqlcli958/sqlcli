import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './app/App';
import './app/brand-theme.css';
import './app/design-system-v3.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
