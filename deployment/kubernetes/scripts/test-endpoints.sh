#!/bin/bash
# TripSphere 服务端点测试脚本

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

NAMESPACE="tripsphere"
FAILED_TESTS=0
PASSED_TESTS=0

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}   TripSphere 服务端点测试${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 测试 HTTP 端点
test_http_endpoint() {
    local SERVICE=$1
    local PORT=$2
    local PATH=$3
    
    echo -n "测试 $SERVICE ($PATH)... "
    
    # 使用 kubectl 执行 curl
    if kubectl run test-curl-$$ --image=curlimages/curl:latest \
        --restart=Never --rm -i --quiet \
        --namespace=$NAMESPACE \
        --command -- curl -s -f -m 5 "http://$SERVICE.$NAMESPACE.svc.cluster.local:$PORT$PATH" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASSED_TESTS++))
    else
        echo -e "${RED}✗ 失败${NC}"
        ((FAILED_TESTS++))
    fi
}

# 测试 gRPC 端点（TCP 连接）
test_grpc_endpoint() {
    local SERVICE=$1
    local PORT=$2
    
    echo -n "测试 $SERVICE:$PORT (gRPC)... "
    
    # 使用 nc 测试 TCP 连接
    if kubectl run test-nc-$$ --image=busybox:1.36 \
        --restart=Never --rm -i --quiet \
        --namespace=$NAMESPACE \
        --command -- nc -zv -w 5 "$SERVICE.$NAMESPACE.svc.cluster.local" "$PORT" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASSED_TESTS++))
    else
        echo -e "${RED}✗ 失败${NC}"
        ((FAILED_TESTS++))
    fi
}

# 检查 Pod 状态
check_pod_status() {
    echo -e "${YELLOW}>>> 检查 Pod 状态...${NC}"
    echo ""
    
    kubectl get pods -n $NAMESPACE
    
    echo ""
    RUNNING_PODS=$(kubectl get pods -n $NAMESPACE --field-selector=status.phase=Running --no-headers | wc -l)
    TOTAL_PODS=$(kubectl get pods -n $NAMESPACE --no-headers | wc -l)
    
    echo "运行中的 Pod: $RUNNING_PODS / $TOTAL_PODS"
    echo ""
}

# 主测试函数
main() {
    check_pod_status
    
    echo -e "${YELLOW}>>> 测试 HTTP 服务端点...${NC}"
    echo ""
    
    # Java Spring Boot 服务 - 使用 /actuator/health
    test_http_endpoint "trip-attraction-service" "24213" "/actuator/health"
    test_http_endpoint "trip-hotel-service" "24214" "/actuator/health"
    test_http_endpoint "trip-note-service" "24216" "/actuator/health"
    test_http_endpoint "trip-user-service" "24217" "/actuator/health"
    
    # Python 服务 - 使用 /health
    test_http_endpoint "trip-chat-service" "24210" "/health"
    test_http_endpoint "trip-itinerary-planner" "24215" "/health"
    test_http_endpoint "trip-journey-assistant" "24211" "/health"
    test_http_endpoint "trip-review-summary" "24212" "/health"
    
    # 前端服务
    test_http_endpoint "trip-next-frontend" "3000" "/"
    
    echo ""
    echo -e "${YELLOW}>>> 测试 gRPC 服务端点...${NC}"
    echo ""
    
    # gRPC 服务
    test_grpc_endpoint "trip-file-service" "50051"
    test_grpc_endpoint "trip-review-service" "50057"
    test_grpc_endpoint "trip-attraction-service" "50053"
    test_grpc_endpoint "trip-hotel-service" "50054"
    test_grpc_endpoint "trip-note-service" "50055"
    test_grpc_endpoint "trip-itinerary-service" "50052"
    test_grpc_endpoint "trip-user-service" "50056"
    
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}   测试结果${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo -e "通过: ${GREEN}$PASSED_TESTS${NC}"
    echo -e "失败: ${RED}$FAILED_TESTS${NC}"
    echo -e "总计: $(($PASSED_TESTS + $FAILED_TESTS))"
    echo ""
    
    if [ $FAILED_TESTS -eq 0 ]; then
        echo -e "${GREEN}✓ 所有测试通过！${NC}"
        exit 0
    else
        echo -e "${RED}✗ 有 $FAILED_TESTS 个测试失败${NC}"
        echo ""
        echo -e "${YELLOW}故障排查建议：${NC}"
        echo "1. 检查失败服务的 Pod 状态:"
        echo "   kubectl get pods -n $NAMESPACE"
        echo ""
        echo "2. 查看失败服务的日志:"
        echo "   kubectl logs -n $NAMESPACE -l app=<service-name> --tail=100"
        echo ""
        echo "3. 检查服务的详细信息:"
        echo "   kubectl describe pod -n $NAMESPACE <pod-name>"
        echo ""
        exit 1
    fi
}

# 执行测试
main
