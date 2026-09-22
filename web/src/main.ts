import { createApp } from "vue";
import { createPinia } from "pinia";
import {
  ElAlert,
  ElAside,
  ElButton,
  ElCard,
  ElCheckbox,
  ElContainer,
  ElDialog,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElHeader,
  ElInput,
  ElLoading,
  ElMain,
  ElOption,
  ElPopconfirm,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import "element-plus/dist/index.css";
import "./style.css";
import App from "./App.vue";
import router from "./router";

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElAlert);
app.use(ElAside);
app.use(ElButton);
app.use(ElCard);
app.use(ElCheckbox);
app.use(ElContainer);
app.use(ElDialog);
app.use(ElDrawer);
app.use(ElEmpty);
app.use(ElForm);
app.use(ElFormItem);
app.use(ElHeader);
app.use(ElInput);
app.use(ElLoading);
app.use(ElMain);
app.use(ElOption);
app.use(ElPopconfirm);
app.use(ElSelect);
app.use(ElSwitch);
app.use(ElTable);
app.use(ElTableColumn);
app.use(ElTag);
app.mount("#app");
