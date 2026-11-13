import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "VITE_");
  return {
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      "/api": env.VITE_BACKEND_TARGET || "http://127.0.0.1:8080",
      "/actuator": env.VITE_BACKEND_TARGET || "http://127.0.0.1:8080",
      "/ai-health": {
        target: env.VITE_AI_TARGET || "http://127.0.0.1:8000",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai-health/, "/health"),
      },
      "/v3/api-docs": env.VITE_BACKEND_TARGET || "http://127.0.0.1:8080",
      "/swagger-ui": env.VITE_BACKEND_TARGET || "http://127.0.0.1:8080",
      "/swagger-ui.html": env.VITE_BACKEND_TARGET || "http://127.0.0.1:8080",
    },
  },
  };
});
