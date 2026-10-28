import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// dev 代理 /api → 9090：前端零 CORS 依赖；联调后端由 lead 保持运行。
// dev 端口 5174（本线任务书指定，避让主仓 5173 的 lead 实例）；strictPort 防静默漂移。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
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
