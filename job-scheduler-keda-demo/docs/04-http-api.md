# 第 04 课：HTTP API

## 单任务

```powershell
$body = @{ jobKey = 'lesson-001'; durationMs = 1000 } | ConvertTo-Json
$event = Invoke-RestMethod http://localhost:18080/api/jobs `
  -Method Post -ContentType application/json -Body $body
$event | ConvertTo-Json
```

请求成功返回 `202 Accepted`。返回体包含 `eventId`、`jobKey`、`durationMs` 和 `createdAt`，表示事件已提交给 RabbitMQ；它不是“任务已完成”。

## 批量任务

```powershell
Invoke-RestMethod 'http://localhost:18080/api/jobs/burst?count=500&durationMs=1000' `
  -Method Post
```

批量接口在 API 内循环发布事件，返回 `prefix`、`count` 和 `durationMs`。

## 校验规则

- `jobKey` 不能为空。
- `durationMs` 必须是 1～60000。
- `count` 必须是 1～500。

`jobKey` 的校验错误返回 400；批量参数不在范围内会被拒绝。RabbitMQ 不可用时，API 返回 5xx，调用方应自行重试或记录失败。

## 健康检查

```powershell
Invoke-RestMethod http://localhost:18080/actuator/health/readiness | ConvertTo-Json -Depth 5
```

readiness 会包含 RabbitMQ 健康状态。当前没有数据库连接检查。

## 练习

1. 为什么接口用 202，而不是等 Worker 完成后返回 200？
2. 修改 `durationMs` 为 60001，观察校验错误。
3. 发送批量请求后，立即查看 RabbitMQ 的 `messages_ready`。
