# 第 11 课：测试与排障

## Maven 测试

```powershell
mvn -B -ntp clean verify
```

`CoreFlowIntegrationTest` 使用 Testcontainers 启动临时 RabbitMQ，调用 `JobApi`，再从队列取回 JSON，验证事件字段。测试不启动 Postgres，也不测试 Scheduler。

## Smoke 和 E2E

```powershell
./scripts/smoke-test.ps1
./scripts/e2e-test.ps1
```

Smoke 验证 readiness、单条发布和队列最终清空；E2E 额外运行 500 条 burst，并检查 Worker 达到 20 后缩回 0。

## 排障顺序

### API 不可用

```powershell
kubectl get pods -n job-demo -l app.kubernetes.io/component=api
kubectl logs -n job-demo -l app.kubernetes.io/component=api --prefix --tail=100
Invoke-RestMethod http://localhost:18080/actuator/health/readiness
```

### 消息没有进入队列

```powershell
docker compose ps
docker exec job-demo-rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

检查 API Pod 的 RabbitMQ host、port、username 和 password 环境变量。

### KEDA 不扩容

```powershell
kubectl describe scaledobject demo-worker-rabbitmq -n job-demo
kubectl get hpa -n job-demo
kubectl logs -n keda deploy/keda-operator --tail=100
```

如果 ready 已经为 0，KEDA 不扩容是正常的；如果 ready 持续大于 0，重点检查 KEDA Secret 和 `host.k3d.internal:15673` 连通性。

## 练习

1. 删除一个 API Pod，观察另一个副本是否仍能接收请求。
2. 暂时缩容 Worker 为 0，再发布慢任务，观察 ready 增长。
3. 沿着 `API → RabbitMQ → KEDA → Worker` 顺序定位一次故障。
