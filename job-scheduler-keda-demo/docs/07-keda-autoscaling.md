# 第 07 课：KEDA 自动扩缩容

## ScaledObject

文件：`kubernetes/keda.yaml`

关键配置：

```yaml
pollingInterval: 5
cooldownPeriod: 60
minReplicaCount: 0
maxReplicaCount: 20
queueName: demo.jobs.ready
value: "1"
```

含义是：空闲时 Worker 可以缩到 0；ready 消息增加时，KEDA 创建 Worker，最多 20 个；队列清空后等待 cooldown 再缩容。

## 观察命令

```powershell
kubectl get scaledobject,hpa -n job-demo -w
kubectl get pods -n job-demo -l app.kubernetes.io/component=worker -w
docker exec job-demo-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

## 为什么不是瞬间到 20

KEDA 每 5 秒轮询一次，随后 HPA 计算副本数，k3d 还要拉起并通过 readiness probe 的 Pod。因此扩容通常需要几十秒，具体取决于镜像和节点资源。

## 练习

1. 把 `maxReplicaCount` 临时改成 5，观察上限。
2. 为什么即使 ready 为 0，Pod 还会保留一段时间？
3. `value: "1"` 与 `value: "10"` 对扩容速度有什么影响？
