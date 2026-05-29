import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    dedupe: ['three'],
  },
  server: {
    port: 5173,
    proxy: {
      "/api/agent": {
        target: "http://127.0.0.1:8081",
        changeOrigin: true,
      },
      "/api": {
        target: "http://127.0.0.1:8081",
        changeOrigin: true,
      },
      "/ws": {
        target: "ws://127.0.0.1:8081",
        ws: true,
        changeOrigin: true,
      },
    },
  },
});
