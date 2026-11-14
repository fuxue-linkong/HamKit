#!/bin/bash

export PATH=/usr/local/bin:/usr/bin:/bin

REPO_DIR="/home/git_repos/HamKit"
WEBSITE_DIR="/var/www/hamkit"
LOG_FILE="/var/log/hamkit_sync.log"

echo "$(date '+%Y-%m-%d %H:%M:%S') - 开始同步..." >> "$LOG_FILE"

# 进入仓库并强制拉取最新代码（放弃本地修改）
cd "$REPO_DIR" || {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - 错误：无法进入目录" >> "$LOG_FILE"
    exit 1
}

git fetch --all >> "$LOG_FILE" 2>&1
git reset --hard origin/miuix >> "$LOG_FILE" 2>&1

# 增量同步 website/ → /var/www/hamkit/：
# 不带 --delete，只复制 git 仓库里存在的文件，绝不删除目标端任何文件。
# - index.html 等网站关键文件手动部署（不进 git），不受本脚本影响
# - tle/ 镜像目录由 mirror_celestrak.sh 独立维护，不受本脚本影响
# --chown 让同步过来的文件直接归 www-data（nginx 可读）
rsync -a --chown=www-data:www-data "$REPO_DIR/website/" "$WEBSITE_DIR/"

echo "$(date '+%Y-%m-%d %H:%M:%S') - 同步完成！" >> "$LOG_FILE"

# 同步完成后兜底刷新 TLE 镜像（脚本内部按 2 小时间隔限频）
/usr/local/bin/mirror_celestrak.sh >> /var/log/hamkit_sync.log 2>&1