# 第 09 课：单任务实验

## 1. 确认基础资源

```powershell
kubectl get pods -n job-demo
kubectl get scaledobject,hpa -n job-demo
```

空闲时应看到 API 2/2、Worker 0 个。

## 2. 发布一个慢任务

```powershell
$body = @{ jobKey = 'single-001'; durationMs = 10000 } | ConvertTo-Json
Invoke-RestMethod http://localhost:18080/api/jobs -Method Post `
  -ContentType application/json -Body $body
```

## 3. 同时观察三处

```powershell
kubectl get pods -n job-demo -l app.kubernetes.io/component=worker -w
docker exec job-demo-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
kubectl logs -n job-demo -l app.kubernetes.io/component=worker --prefix -f
```

预期顺序：消息先进入 ready，KEDA 创建 Worker，消息转为 unacked，约 10 秒后 Worker 完成并确认，队列回到 0，之后 Pod 缩到 0。

## 练习

1. 哪一刻 KEDA 从 0 变为 1？
2. 为什么 API 返回成功时任务还没有完成？
3. 这条链路中没有哪个组件负责保存最终状态？
