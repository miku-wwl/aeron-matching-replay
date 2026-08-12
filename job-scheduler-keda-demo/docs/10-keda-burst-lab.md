# 第 10 课：500 个任务冲击 KEDA

## 运行实验

```powershell
./scripts/demo-burst.ps1 -Count 500 -DurationMs 1000 -TargetPods 20
```

脚本调用 `/api/jobs/burst`，然后循环采样 Worker desired/ready 副本数，以及 RabbitMQ `messages_ready` 和 `messages_unacknowledged`，并检查是否已经达到 20 个 Ready Pod。

## 观察

```powershell
kubectl get pods -n job-demo -l app.kubernetes.io/component=worker -w
kubectl get hpa -n job-demo -w
```

预期：ready 队列快速升高，Worker 逐步扩到 20；消费速度提升后 ready 归零，等待 cooldown 后 desired 回到 0。

## 常见现象

- 任务很快完成时，队列可能在达到 20 Pod 前就清空，这是负载太轻，不一定是 KEDA 错误。
- `TargetPods` 只是验收阈值，实际副本数还受轮询周期、镜像启动时间和节点资源影响。
- unacked 不为 0 说明 Worker 仍在处理，即使 ready 已经为 0。

## 练习

1. 把任务时长改为 5000ms，再看扩容曲线。
2. 只发布 5 个任务，为什么可能不会到 20 个 Pod？
3. 解释为什么 KEDA 监控 RabbitMQ，而不是监控 API 请求数量。
