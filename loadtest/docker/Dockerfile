# ============================================================
# JMeter 压测容器（本地构建，避免依赖 Docker Hub 上的第三方镜像）
#
# 构建： docker build -t geek-mall-jmeter:5.6.3 loadtest/docker
# 运行： docker run --rm -v "$PWD/loadtest":/loadtest geek-mall-jmeter:5.6.3 \
#          -n -t /loadtest/jmeter/seckill-stress.jmx -l /loadtest/results/out.jtl
# ============================================================
FROM eclipse-temurin:21-jre

ARG JMETER_VERSION=5.6.3

# 发行包在构建期从 Apache 官方镜像下载，不再随仓库提交 87MB 的二进制归档。
# 下载后校验 SHA512（官方 .sha512 文件同源下载），保证可复现且防篡改。
# 若构建机无外网，可预先 `docker build` 时挂载缓存层或用 --build-arg 指定内网镜像地址。
ARG JMETER_DIST_URL=https://archive.apache.org/dist/jmeter/binaries
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL -o /tmp/jmeter.tgz "${JMETER_DIST_URL}/apache-jmeter-${JMETER_VERSION}.tgz"; \
    curl -fsSL -o /tmp/jmeter.tgz.sha512 "${JMETER_DIST_URL}/apache-jmeter-${JMETER_VERSION}.tgz.sha512"; \
    (cd /tmp && sha512sum -c jmeter.tgz.sha512); \
    tar -xzf /tmp/jmeter.tgz -C /opt; \
    rm -f /tmp/jmeter.tgz /tmp/jmeter.tgz.sha512; \
    ln -s /opt/apache-jmeter-${JMETER_VERSION}/bin/jmeter /usr/local/bin/jmeter; \
    ln -s /opt/apache-jmeter-${JMETER_VERSION}/bin/jmeter-server /usr/local/bin/jmeter-server

ENV JMETER_HOME=/opt/apache-jmeter-${JMETER_VERSION}
ENV PATH="${JMETER_HOME}/bin:${PATH}"

WORKDIR /loadtest
ENTRYPOINT ["jmeter"]
CMD ["--version"]
