import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return;
          if (
            id.includes("/element-plus/es/components/date-picker/") ||
            id.includes("/element-plus/es/components/time-picker/") ||
            id.includes("/dayjs/")
          ) {
            return "date-tools";
          }
          if (id.includes("element-plus")) return "element-plus";
          if (
            id.includes("/vue/") ||
            id.includes("vue-router") ||
            id.includes("pinia")
          ) {
            return "vue-vendor";
          }
          if (id.includes("axios")) return "http-client";
          if (id.includes("/zrender/")) return "chart-renderer";
          if (id.includes("echarts")) return "charts";
        },
      },
    },
  },
});
