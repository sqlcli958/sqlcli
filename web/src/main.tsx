import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './app/App';
import './app/App.css';
import './app/brand-theme.css';
import './app/product-layout.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
