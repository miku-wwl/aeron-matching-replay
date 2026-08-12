# 第 01 课：本地环境

## 目标

理解 Docker、k3d、kubectl、Helm 在本 Demo 中分别做什么，以及 RabbitMQ 为什么运行在 Docker 而不是 k3d Pod 内。

## 组件拓扑

```text
Windows 主机
├─ Docker Compose
│  └─ RabbitMQ（AMQP 15673，管理 UI 15674）
└─ k3d / K3s
   ├─ API Deployment（2 副本）
   ├─ Worker Deployment（由 KEDA 0～20）
   ├─ KEDA Operator
   └─ ScaledObject / HPA
```

应用 Pod 通过 `host.k3d.internal:15673` 访问宿主机上的 RabbitMQ。API NodePort 映射为 `localhost:18080`。

## 关键脚本

| 脚本 | 作用 |
|---|---|
| `prerequisites.ps1` | 检查并安装 k3d、Helm CLI |
| `up-infra.ps1` | 启动 RabbitMQ Docker 容器 |
| `create-cluster.ps1` | 创建 k3d 集群并安装 KEDA |
| `deploy.ps1` | 构建镜像、导入 k3d、部署 API/Worker/KEDA |
| `verify.ps1` | Maven 验证和 Kubernetes 资源检查 |

## 动手检查

```powershell
docker compose ps
kubectl get nodes -o wide
kubectl get pods -A
kubectl get scaledobject,hpa -n job-demo
```

预期看到 RabbitMQ healthy、2 个 API Pod，以及空闲时 0 个 Worker Pod。

## 练习

1. 找出 k3d 节点的 IP，并解释 `host.k3d.internal` 的用途。
2. 为什么 RabbitMQ 不需要复制成 Kubernetes Deployment 才能完成本地 Demo？
3. 删除并重新创建 k3d 集群后，RabbitMQ 的 Docker 数据是否还在？
