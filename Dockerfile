# ===== 构建阶段：Maven 拉依赖 + 打包（跳过测试，测试在 CI/本地跑） =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 先拷 pom 拉依赖，源码变更时利用层缓存
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# ===== 运行阶段：仅 JRE，镜像最小化 =====
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/zhixing-0.0.1-SNAPSHOT.jar app.jar
ENV TZ=Asia/Shanghai
EXPOSE 9090
# JAVA_OPTS 可选注入（如 JVM 代理参数 -Dhttp.proxyHost=...），默认空——不写死代理
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
