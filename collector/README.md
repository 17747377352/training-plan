# Training Plan Collector

Garmin 数据采集进程，负责登录 Garmin Connect、消费 Redis 同步任务、归档原始响应并向 Java 服务提交标准化数据。

## 本地运行

```bash
cp .env.example .env.dev
uv sync
uv run training-plan-collector
```

当前骨架会检查 Redis 连接并退出；后续阶段再接入常驻任务消费循环。

## Garmin typed 模型

项目固定使用 `garminconnect[typed]==0.3.16`。每日统计、睡眠概览、HRV、训练准备度、活动和 Body Battery 优先复用库内 Pydantic 模型。分钟级数组等未覆盖内容保留原始 `dict`，由独立适配器转换。
