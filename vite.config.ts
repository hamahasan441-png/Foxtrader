import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  resolve: {
    alias: {
      '@core': resolve(__dirname, 'src/core'),
      '@modules': resolve(__dirname, 'src/modules'),
      '@engine': resolve(__dirname, 'src/engine'),
      '@ui': resolve(__dirname, 'src/ui'),
      '@utils': resolve(__dirname, 'src/utils'),
    },
  },
  build: {
    target: 'es2022',
    minify: 'terser',
    rollupOptions: {
      output: {
        manualChunks: {
          'chart-engine': ['src/engine/chart-engine.ts'],
          'modules': ['src/modules/index.ts'],
          'scanner': ['src/modules/scanner/index.ts'],
        },
      },
    },
  },
  worker: {
    format: 'es',
  },
});
