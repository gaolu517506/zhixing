import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// dev 代理 /api → 9090：前端零 CORS 依赖；联调后端由 lead 保持运行。
// VITE_PROXY_TARGET 可覆盖代理目标——纯前端 mock 态走查时指向死端口，
// 避免真实后端对 mock token 回 401 触发全局登出拦截（T6E 走查用）。
// dev 端口 5174（M1 任务书指定）；strictPort 防静默漂移，命令行 --port 可再覆盖。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.VITE_PROXY_TARGET ?? "http://localhost:9090",
        changeOrigin: true,
      },
    },
  },
  test: {
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    environment: "node",
  },
});
