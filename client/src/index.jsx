import React from 'react';
import ReactDOM from 'react-dom/client';
// Self-hosted Playfair Display (replaces the Google Fonts <link>). These are the exact
// weights/styles the previous fonts.googleapis.com URL requested and that --serif-font
// in App.css relies on: 400/700, normal + italic. Bundling them locally lets the CSP
// drop the fonts.googleapis.com / fonts.gstatic.com exceptions.
import '@fontsource/playfair-display/400.css';
import '@fontsource/playfair-display/700.css';
import '@fontsource/playfair-display/400-italic.css';
import '@fontsource/playfair-display/700-italic.css';
import './index.css';
import App from './App';

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
