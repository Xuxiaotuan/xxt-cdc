#!/bin/bash

# MySQL CDC Service 构建脚本

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

# 检查依赖
check_dependencies() {
    log_info "检查构建依赖..."
    
    if ! command -v sbt &> /dev/null; then
        log_error "SBT 未安装，请先安装 SBT"
        exit 1
    fi
    
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    log_info "依赖检查完成"
}

# 清理构建产物
clean_build() {
    log_info "清理构建产物..."
    sbt clean
    rm -rf target/
    log_info "清理完成"
}

# 编译项目
compile_project() {
    log_info "编译项目..."
    sbt compile
    log_info "编译完成"
}

# 运行测试
run_tests() {
    log_info "运行测试..."
    sbt test
    log_info "测试完成"
}

# 打包应用
package_app() {
    log_info "打包应用..."
    sbt assembly
    
    # 检查 JAR 文件是否生成
    JAR_FILE=$(find target/scala-2.13 -name "*-assembly-*.jar" | head -1)
    if [ -z "$JAR_FILE" ]; then
        log_error "JAR 文件生成失败"
        exit 1
    fi
    
    log_info "应用打包完成: $JAR_FILE"
}

# 构建 Docker 镜像
build_docker_image() {
    local version=${1:-latest}
    local image_name="mysql-cdc-service:$version"
    
    log_info "构建 Docker 镜像: $image_name"
    
    # 确保 entrypoint.sh 有执行权限
    chmod +x docker/entrypoint.sh
    
    docker build -t "$image_name" .
    
    log_info "Docker 镜像构建完成: $image_name"
}

# 推送 Docker 镜像
push_docker_image() {
    local version=${1:-latest}
    local registry=${2:-""}
    local image_name="mysql-cdc-service:$version"
    
    if [ -n "$registry" ]; then
        local full_image_name="$registry/$image_name"
        log_info "标记镜像: $full_image_name"
        docker tag "$image_name" "$full_image_name"
        
        log_info "推送镜像到注册表: $full_image_name"
        docker push "$full_image_name"
    else
        log_warn "未指定镜像注册表，跳过推送"
    fi
}

# 生成版本信息
generate_version() {
    local version_file="src/main/resources/version.properties"
    local git_commit=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    local build_time=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    local version=${1:-"1.0.0-SNAPSHOT"}
    
    log_info "生成版本信息..."
    
    mkdir -p "$(dirname "$version_file")"
    cat > "$version_file" << EOF
version=$version
git.commit=$git_commit
build.time=$build_time
EOF
    
    log_info "版本信息已生成: $version_file"
}

# 主函数
main() {
    local command=${1:-"all"}
    local version=${2:-"latest"}
    local registry=${3:-""}
    
    case $command in
        "clean")
            clean_build
            ;;
        "compile")
            check_dependencies
            compile_project
            ;;
        "test")
            check_dependencies
            run_tests
            ;;
        "package")
            check_dependencies
            generate_version "$version"
            compile_project
            package_app
            ;;
        "docker")
            check_dependencies
            generate_version "$version"
            compile_project
            package_app
            build_docker_image "$version"
            ;;
        "push")
            check_dependencies
            generate_version "$version"
            compile_project
            package_app
            build_docker_image "$version"
            push_docker_image "$version" "$registry"
            ;;
        "all")
            check_dependencies
            clean_build
            generate_version "$version"
            compile_project
            run_tests
            package_app
            build_docker_image "$version"
            ;;
        *)
            echo "用法: $0 {clean|compile|test|package|docker|push|all} [version] [registry]"
            echo ""
            echo "命令说明:"
            echo "  clean    - 清理构建产物"
            echo "  compile  - 编译项目"
            echo "  test     - 运行测试"
            echo "  package  - 打包应用"
            echo "  docker   - 构建 Docker 镜像"
            echo "  push     - 构建并推送 Docker 镜像"
            echo "  all      - 执行完整构建流程（默认）"
            echo ""
            echo "参数说明:"
            echo "  version  - 版本号（默认: latest）"
            echo "  registry - Docker 镜像注册表地址"
            echo ""
            echo "示例:"
            echo "  $0 all 1.0.0"
            echo "  $0 push 1.0.0 registry.example.com"
            exit 1
            ;;
    esac
    
    log_info "构建完成！"
}

# 执行主函数
main "$@"