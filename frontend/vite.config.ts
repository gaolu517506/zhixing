import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// dev 代理 /api → 9090：前端零 CORS 依赖；联调后端由 lead 保持运行
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:9090",
        changeOrigin: true,
      },
    },
  },
  test: {
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    environment: "node",
  },
});
