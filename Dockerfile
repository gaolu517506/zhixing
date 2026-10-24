# ===== 构建阶段：Maven 拉依赖 + 多模块打包（跳过测试，测试在 CI/本地跑） =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷 pom 拉依赖（多模块：全部 pom + 模块目录占位），源码变更时利用层缓存
COPY pom.xml .
COPY zhixing-framework/pom.xml zhixing-framework/
COPY zhixing-llm/pom.xml zhixing-llm/
COPY zhixing-core/pom.xml zhixing-core/
COPY zhixing-bootstrap/pom.xml zhixing-bootstrap/
RUN mvn -q dependency:go-offline
COPY zhixing-framework/src ./zhixing-framework/src
COPY zhixing-llm/src ./zhixing-llm/src
COPY zhixing-core/src ./zhixing-core/src
COPY zhixing-bootstrap/src ./zhixing-bootstrap/src
RUN mvn -q package -DskipTests

# ===== 运行阶段：仅 JRE，镜像最小化（可执行 jar 在 bootstrap 模块） =====
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/zhixing-bootstrap/target/zhixing-bootstrap-0.0.1-SNAPSHOT.jar app.jar
ENV TZ=Asia/Shanghai
EXPOSE 9090
# JAVA_OPTS 可选注入（如 JVM 代理参数 -Dhttp.proxyHost=...），默认空——不写死代理
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
