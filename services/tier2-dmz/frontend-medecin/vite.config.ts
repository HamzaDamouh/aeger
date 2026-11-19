import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Application servie par NGINX à la racine du site (repli SPA vers index.html).
// Sortie : dist/index.html + dist/assets/* (noms avec empreinte) + fichiers de public/.
export default defineConfig({
  base: '/',
  plugins: [react()],
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    // Aucun script en ligne dans index.html (CSP script-src 'self') : pas de polyfill injecté.
    modulePreload: { polyfill: false },
    sourcemap: false,
  },
});
