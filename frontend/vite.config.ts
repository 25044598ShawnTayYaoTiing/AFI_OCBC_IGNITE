import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/actuator": "http://127.0.0.1:8080",
      "/ai-health": {
        target: "http://127.0.0.1:8000",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai-health/, "/health"),
      },
      "/v3/api-docs": "http://127.0.0.1:8080",
      "/swagger-ui": "http://127.0.0.1:8080",
      "/swagger-ui.html": "http://127.0.0.1:8080",
    },
  },
});
