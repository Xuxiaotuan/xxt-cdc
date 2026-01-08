#!/bin/bash

# MySQL CDC Service 部署脚本

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_debug() {
    echo -e "${BLUE}[DEBUG]${NC} $1"
}

# 配置变量
COMPOSE_FILE="docker-compose.yml"
PROJECT_NAME="mysql-cdc"
HEALTH_CHECK_TIMEOUT=300  # 5分钟
HEALTH_CHECK_INTERVAL=10  # 10秒

# 检查 Docker 和 Docker Compose
check_docker() {
    log_info "检查 Docker 环境..."
    
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
    
    # 检查 Docker 服务是否运行
    if ! docker info &> /dev/null; then
        log_error "Docker 服务未运行，请启动 Docker 服务"
        exit 1
    fi
    
    log_info "Docker 环境检查完成"
}

# 创建必要的目录
create_directories() {
    log_info "创建必要的目录..."
    
    mkdir -p docker/mysql
    mkdir -p docker/prometheus
    mkdir -p docker/grafana/provisioning/datasources
    mkdir -p docker/grafana/provisioning/dashboards
    mkdir -p docker/grafana/dashboards
    mkdir -p logs
    mkdir -p data
    
    log_info "目录创建完成"
}

# 检查配置文件
check_config_files() {
    log_info "检查配置文件..."
    
    local required_files=(
        "docker-compose.yml"
        "docker/application.conf"
        "docker/logback.xml"
        "docker/entrypoint.sh"
        "docker/mysql/source-init.sql"
        "docker/mysql/target-init.sql"
        "docker/prometheus/prometheus.yml"
    )
    
    for file in "${required_files[@]}"; do
        if [ ! -f "$file" ]; then
            log_error "配置文件不存在: $file"
            exit 1
        fi
    done
    
    # 确保脚本有执行权限
    chmod +x docker/entrypoint.sh
    
    log_info "配置文件检查完成"
}

# 启动服务
start_services() {
    log_info "启动 MySQL CDC 服务..."
    
    # 拉取最新镜像
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" pull
    
    # 启动服务
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" up -d
    
    log_info "服务启动完成"
}

# 停止服务
stop_services() {
    log_info "停止 MySQL CDC 服务..."
    
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" down
    
    log_info "服务停止完成"
}

# 重启服务
restart_services() {
    log_info "重启 MySQL CDC 服务..."
    
    stop_services
    start_services
    
    log_info "服务重启完成"
}

# 检查服务健康状态
check_health() {
    log_info "检查服务健康状态..."
    
    local services=("source-mysql" "target-mysql" "cdc-service")
    local start_time=$(date +%s)
    
    for service in "${services[@]}"; do
        log_info "等待 $service 服务就绪..."
        
        while true; do
            local current_time=$(date +%s)
            local elapsed=$((current_time - start_time))
            
            if [ $elapsed -gt $HEALTH_CHECK_TIMEOUT ]; then
                log_error "$service 服务健康检查超时"
                return 1
            fi
            
            local health_status=$(docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" ps -q "$service" | xargs docker inspect --format='{{.State.Health.Status}}' 2>/dev/null || echo "unknown")
            
            if [ "$health_status" = "healthy" ]; then
                log_info "$service 服务已就绪"
                break
            elif [ "$health_status" = "unhealthy" ]; then
                log_error "$service 服务不健康"
                return 1
            fi
            
            sleep $HEALTH_CHECK_INTERVAL
        done
    done
    
    log_info "所有服务健康检查完成"
}

# 查看服务状态
show_status() {
    log_info "服务状态:"
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" ps
    
    echo ""
    log_info "服务访问地址:"
    echo "  CDC Service API: http://localhost:8080"
    echo "  CDC Service Health: http://localhost:8080/health"
    echo "  Prometheus Metrics: http://localhost:9090/metrics"
    echo "  Prometheus UI: http://localhost:9091"
    echo "  Grafana UI: http://localhost:3000 (admin/admin)"
    echo "  Source MySQL: localhost:3306"
    echo "  Target MySQL: localhost:3307"
}

# 查看服务日志
show_logs() {
    local service=${1:-""}
    local follow=${2:-false}
    
    if [ -n "$service" ]; then
        if [ "$follow" = true ]; then
            log_info "跟踪 $service 服务日志 (Ctrl+C 退出)..."
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" logs -f "$service"
        else
            log_info "显示 $service 服务日志..."
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" logs "$service"
        fi
    else
        if [ "$follow" = true ]; then
            log_info "跟踪所有服务日志 (Ctrl+C 退出)..."
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" logs -f
        else
            log_info "显示所有服务日志..."
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" logs
        fi
    fi
}

# 清理数据
cleanup_data() {
    log_warn "这将删除所有数据卷，包括数据库数据！"
    read -p "确认继续？(y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        log_info "停止服务并清理数据..."
        docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" down -v
        docker system prune -f
        log_info "数据清理完成"
    else
        log_info "取消清理操作"
    fi
}

# 备份数据
backup_data() {
    local backup_dir="backups/$(date +%Y%m%d_%H%M%S)"
    
    log_info "创建数据备份到: $backup_dir"
    mkdir -p "$backup_dir"
    
    # 备份源数据库
    log_info "备份源数据库..."
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" exec -T source-mysql \
        mysqldump -u root -ppassword --all-databases --single-transaction --routines --triggers \
        > "$backup_dir/source_mysql_backup.sql"
    
    # 备份目标数据库
    log_info "备份目标数据库..."
    docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" exec -T target-mysql \
        mysqldump -u root -ppassword --all-databases --single-transaction --routines --triggers \
        > "$backup_dir/target_mysql_backup.sql"
    
    # 备份配置文件
    log_info "备份配置文件..."
    cp -r docker "$backup_dir/"
    cp docker-compose.yml "$backup_dir/"
    
    log_info "数据备份完成: $backup_dir"
}

# 恢复数据
restore_data() {
    local backup_dir=$1
    
    if [ -z "$backup_dir" ] || [ ! -d "$backup_dir" ]; then
        log_error "请指定有效的备份目录"
        exit 1
    fi
    
    log_warn "这将覆盖现有数据！"
    read -p "确认继续？(y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        log_info "从备份恢复数据: $backup_dir"
        
        # 恢复源数据库
        if [ -f "$backup_dir/source_mysql_backup.sql" ]; then
            log_info "恢复源数据库..."
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" exec -T source-mysql \
                mysql -u root -ppassword < "$backup_dir/source_mysql_backup.sql"
        fi
        
        # 恢复目标数据库
        if [ -f "$backup_dir/target_mysql_backup.sql" ]; then
            log_info "恢复目标数据库..."
            docker-compose -f "$COMPOSE_FILE" -p "$PROJECT_NAME" exec -T target-mysql \
                mysql -u root -ppassword < "$backup_dir/target_mysql_backup.sql"
        fi
        
        log_info "数据恢复完成"
    else
        log_info "取消恢复操作"
    fi
}

# 主函数
main() {
    local command=${1:-"start"}
    local service=${2:-""}
    local follow=${3:-false}
    
    case $command in
        "start")
            check_docker
            create_directories
            check_config_files
            start_services
            check_health
            show_status
            ;;
        "stop")
            stop_services
            ;;
        "restart")
            restart_services
            check_health
            show_status
            ;;
        "status")
            show_status
            ;;
        "logs")
            show_logs "$service" "$follow"
            ;;
        "health")
            check_health
            ;;
        "cleanup")
            cleanup_data
            ;;
        "backup")
            backup_data
            ;;
        "restore")
            restore_data "$service"
            ;;
        *)
            echo "用法: $0 {start|stop|restart|status|logs|health|cleanup|backup|restore} [service] [follow]"
            echo ""
            echo "命令说明:"
            echo "  start    - 启动所有服务"
            echo "  stop     - 停止所有服务"
            echo "  restart  - 重启所有服务"
            echo "  status   - 查看服务状态"
            echo "  logs     - 查看服务日志"
            echo "  health   - 检查服务健康状态"
            echo "  cleanup  - 清理所有数据（危险操作）"
            echo "  backup   - 备份数据"
            echo "  restore  - 恢复数据"
            echo ""
            echo "参数说明:"
            echo "  service  - 指定服务名称（可选）"
            echo "  follow   - 跟踪日志输出（true/false）"
            echo ""
            echo "示例:"
            echo "  $0 start"
            echo "  $0 logs cdc-service true"
            echo "  $0 restore backups/20240101_120000"
            exit 1
            ;;
    esac
}

# 执行主函数
main "$@"