#!/bin/bash
# TripSphere Kubernetes 一键部署脚本

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

NAMESPACE="tripsphere"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(dirname "$SCRIPT_DIR")"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}   TripSphere Kubernetes 部署脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 检查必要工具
check_requirements() {
    echo -e "${YELLOW}>>> 检查必要工具...${NC}"
    
    if ! command -v kubectl &> /dev/null; then
        echo -e "${RED}错误: kubectl 未安装${NC}"
        exit 1
    fi
    
    if ! command -v helm &> /dev/null; then
        echo -e "${RED}错误: helm 未安装${NC}"
        exit 1
    fi
    
    # 检查集群连接
    if ! kubectl cluster-info &> /dev/null; then
        echo -e "${RED}错误: 无法连接到 Kubernetes 集群${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ 所有必要工具已就绪${NC}"
    echo ""
}

# 创建 namespace 和基础资源
deploy_base() {
    echo -e "${YELLOW}>>> 创建基础资源...${NC}"
    
    kubectl apply -f "$K8S_DIR/manifests/00-namespace.yaml"
    kubectl apply -f "$K8S_DIR/manifests/01-configmap.yaml"
    
    # 检查 secrets 文件
    if [ ! -f "$K8S_DIR/manifests/02-secrets.yaml" ]; then
        echo -e "${RED}错误: manifests/02-secrets.yaml 不存在${NC}"
        echo -e "${YELLOW}请先配置 secrets.yaml:${NC}"
        echo "  cp manifests/02-secrets.yaml.example manifests/02-secrets.yaml"
        echo "  vim manifests/02-secrets.yaml  # 配置实际的密码和 API Key"
        exit 1
    fi
    
    kubectl apply -f "$K8S_DIR/manifests/02-secrets.yaml"
    
    echo -e "${GREEN}✓ 基础资源创建完成${NC}"
    echo ""
}

# 添加 Helm 仓库
add_helm_repos() {
    echo -e "${YELLOW}>>> 添加 Helm 仓库...${NC}"
    
    helm repo add bitnami https://charts.bitnami.com/bitnami || true
    helm repo add minio https://charts.min.io/ || true
    helm repo add neo4j https://helm.neo4j.com/neo4j || true
    helm repo add qdrant https://qdrant.github.io/qdrant-helm || true
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts || true
    helm repo update
    
    echo -e "${GREEN}✓ Helm 仓库添加完成${NC}"
    echo ""
}

# 部署中间件
deploy_middlewares() {
    echo -e "${YELLOW}>>> 部署中间件...${NC}"
    echo -e "${YELLOW}这可能需要 10-15 分钟，请耐心等待...${NC}"
    echo ""
    
    # 第一批：数据库
    echo -e "${YELLOW}[1/4] 部署数据库...${NC}"
    
    helm upgrade --install mongodb bitnami/mongodb \
        --namespace $NAMESPACE \
        --values "$K8S_DIR/helm-values/mongodb-values.yaml" \
        --wait --timeout 5m || echo -e "${RED}MongoDB 部署失败${NC}"
    
    helm upgrade --install mysql bitnami/mysql \
        --namespace $NAMESPACE \
        --values "$K8S_DIR/helm-values/mysql-values.yaml" \
        --wait --timeout 5m || echo -e "${RED}MySQL 部署失败${NC}"
    
    helm upgrade --install redis bitnami/redis \
        --namespace $NAMESPACE \
        --values "$K8S_DIR/helm-values/redis-values.yaml" \
        --wait --timeout 5m || echo -e "${RED}Redis 部署失败${NC}"
    
    helm upgrade --install neo4j neo4j/neo4j \
        --namespace $NAMESPACE \
        --values "$K8S_DIR/helm-values/neo4j-values.yaml" \
        --wait --timeout 5m || echo -e "${RED}Neo4j 部署失败${NC}"
    
    echo -e "${GREEN}✓ 数据库部署完成${NC}"
    echo ""
    
    # 第二批：存储和向量数据库
    echo -e "${YELLOW}[2/4] 部署存储服务...${NC}"
    
    helm upgrade --install minio minio/minio \
        --namespace $NAMESPACE \
        --values "$K8S_DIR/helm-values/minio-values.yaml" \
        --wait --timeout 5m || echo -e "${RED}MinIO 部署失败${NC}"
    
    helm upgrade --install qdrant qdrant/qdrant \
        --namespace $NAMESPACE \
        --values "$K8S_DIR/helm-values/qdrant-values.yaml" \
        --wait --timeout 5m || echo -e "${RED}Qdrant 部署失败${NC}"
    
    echo -e "${GREEN}✓ 存储服务部署完成${NC}"
    echo ""
    
    # 第三批：Nacos（需要手动部署）
    echo -e "${YELLOW}[3/4] Nacos 需要手动部署${NC}"
    echo -e "${YELLOW}请参考: MIDDLEWARE_DEPLOYMENT_GUIDE.md${NC}"
    echo -e "${YELLOW}使用 nacos-k8s 项目部署${NC}"
    echo ""
    
    # 第四批：RocketMQ（需要手动部署）
    echo -e "${YELLOW}[4/4] RocketMQ 需要手动部署${NC}"
    echo -e "${YELLOW}请参考: MIDDLEWARE_DEPLOYMENT_GUIDE.md${NC}"
    echo -e "${YELLOW}使用 OCI Helm Chart 部署${NC}"
    echo ""
    
    echo -e "${GREEN}✓ 中间件部署完成（部分需要手动部署）${NC}"
    echo ""
}

# 等待中间件就绪
wait_for_middlewares() {
    echo -e "${YELLOW}>>> 等待中间件就绪...${NC}"
    
    echo "等待 MongoDB..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=mongodb -n $NAMESPACE --timeout=300s || true
    
    echo "等待 MySQL..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=mysql -n $NAMESPACE --timeout=300s || true
    
    echo "等待 Redis..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=redis -n $NAMESPACE --timeout=300s || true
    
    echo "等待 MinIO..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=minio -n $NAMESPACE --timeout=300s || true
    
    echo "等待 Qdrant..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=qdrant -n $NAMESPACE --timeout=300s || true
    
    echo "等待 Neo4j..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=neo4j -n $NAMESPACE --timeout=300s || true
    
    echo -e "${GREEN}✓ 中间件已就绪${NC}"
    echo ""
}

# 部署业务服务
deploy_services() {
    echo -e "${YELLOW}>>> 部署业务服务...${NC}"
    echo -e "${YELLOW}这可能需要 5-10 分钟...${NC}"
    echo ""
    
    # 批量部署所有服务
    kubectl apply -f "$K8S_DIR/manifests/services/"
    
    echo ""
    echo -e "${YELLOW}等待服务就绪...${NC}"
    sleep 10
    
    # 检查部署状态
    kubectl get pods -n $NAMESPACE
    
    echo -e "${GREEN}✓ 业务服务部署完成${NC}"
    echo ""
}

# 部署 Ingress
deploy_ingress() {
    echo -e "${YELLOW}>>> 部署 Ingress...${NC}"
    
    kubectl apply -f "$K8S_DIR/manifests/ingress/"
    
    echo -e "${GREEN}✓ Ingress 部署完成${NC}"
    echo ""
}

# 验证部署
verify_deployment() {
    echo -e "${YELLOW}>>> 验证部署状态...${NC}"
    echo ""
    
    echo "=== Pods ==="
    kubectl get pods -n $NAMESPACE
    echo ""
    
    echo "=== Services ==="
    kubectl get svc -n $NAMESPACE
    echo ""
    
    echo "=== Ingress ==="
    kubectl get ingress -n $NAMESPACE
    echo ""
    
    # 检查失败的 Pod
    FAILED_PODS=$(kubectl get pods -n $NAMESPACE --field-selector=status.phase!=Running --no-headers | wc -l)
    if [ "$FAILED_PODS" -gt 0 ]; then
        echo -e "${RED}警告: 有 $FAILED_PODS 个 Pod 未正常运行${NC}"
        kubectl get pods -n $NAMESPACE --field-selector=status.phase!=Running
    else
        echo -e "${GREEN}✓ 所有 Pod 都在运行中${NC}"
    fi
    
    echo ""
}

# 显示访问信息
show_access_info() {
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}   部署完成！${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "${YELLOW}访问前端：${NC}"
    echo "  方式 1 (端口转发):"
    echo "    kubectl port-forward -n tripsphere svc/trip-next-frontend 3000:3000"
    echo "    然后访问: http://localhost:3000"
    echo ""
    echo "  方式 2 (Ingress):"
    echo "    配置 DNS: <ingress-ip> tripsphere.local"
    echo "    访问: http://tripsphere.local"
    echo ""
    echo -e "${YELLOW}查看状态：${NC}"
    echo "  kubectl get pods -n tripsphere"
    echo "  kubectl get svc -n tripsphere"
    echo ""
    echo -e "${YELLOW}查看日志：${NC}"
    echo "  kubectl logs -n tripsphere -l app=trip-chat-service --tail=100 -f"
    echo ""
}

# 主函数
main() {
    # 解析参数
    SKIP_MIDDLEWARES=false
    SKIP_SERVICES=false
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-middlewares)
                SKIP_MIDDLEWARES=true
                shift
                ;;
            --skip-services)
                SKIP_SERVICES=true
                shift
                ;;
            --help)
                echo "用法: $0 [选项]"
                echo ""
                echo "选项:"
                echo "  --skip-middlewares    跳过中间件部署"
                echo "  --skip-services       跳过业务服务部署"
                echo "  --help                显示帮助信息"
                exit 0
                ;;
            *)
                echo "未知选项: $1"
                echo "使用 --help 查看帮助"
                exit 1
                ;;
        esac
    done
    
    # 执行部署
    check_requirements
    deploy_base
    
    if [ "$SKIP_MIDDLEWARES" = false ]; then
        add_helm_repos
        deploy_middlewares
        wait_for_middlewares
    else
        echo -e "${YELLOW}>>> 跳过中间件部署${NC}"
        echo ""
    fi
    
    if [ "$SKIP_SERVICES" = false ]; then
        deploy_services
        deploy_ingress
    else
        echo -e "${YELLOW}>>> 跳过业务服务部署${NC}"
        echo ""
    fi
    
    verify_deployment
    show_access_info
}

# 执行主函数
main "$@"
