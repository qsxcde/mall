# ============================================================
# JMeter 压测容器（本地构建，避免依赖 Docker Hub 上的第三方镜像）
#
# 构建： docker build -t geek-mall-jmeter:5.6.3 loadtest/docker
# 运行： docker run --rm -v "$PWD/loadtest":/loadtest geek-mall-jmeter:5.6.3 \
#          -n -t /loadtest/jmeter/seckill-stress.jmx -l /loadtest/results/out.jtl
# ============================================================
FROM eclipse-temurin:21-jre

ARG JMETER_VERSION=5.6.3

# 直接使用已下载好的发行包，构建期不依赖外网
COPY apache-jmeter-${JMETER_VERSION}.tgz /tmp/jmeter.tgz

RUN tar -xzf /tmp/jmeter.tgz -C /opt \
    && rm /tmp/jmeter.tgz \
    && ln -s /opt/apache-jmeter-${JMETER_VERSION}/bin/jmeter /usr/local/bin/jmeter \
    && ln -s /opt/apache-jmeter-${JMETER_VERSION}/bin/jmeter-server /usr/local/bin/jmeter-server

ENV JMETER_HOME=/opt/apache-jmeter-${JMETER_VERSION}
ENV PATH="${JMETER_HOME}/bin:${PATH}"

WORKDIR /loadtest
ENTRYPOINT ["jmeter"]
CMD ["--version"]
