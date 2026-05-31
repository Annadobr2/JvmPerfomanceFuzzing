FROM openjdk:17-jdk-slim as base

RUN apt-get update && apt-get install -y \
    wget curl unzip \
    && apt-get clean

# HotSpot уже включен в базовый образ
# Если нужна конкретная версия HotSpot, раскомментируйте и настройте блок ниже:
# RUN wget -q https://download.java.net/java/GA/jdk17.0.2/dfd4a8d0985749f896bed50d7138ee7f/8/GPL/openjdk-17.0.2_linux-x64_bin.tar.gz \
#     && mkdir -p /opt/hotspot \
#     && tar -xzf openjdk-17.0.2_linux-x64_bin.tar.gz -C /opt/hotspot --strip-components=1 \
#     && rm openjdk-17.0.2_linux-x64_bin.tar.gz

# GraalVM
RUN wget -q https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-17.0.9/graalvm-community-jdk-17.0.9_linux-x64_bin.tar.gz \
    && mkdir -p /opt/graalvm \
    && tar -xzf graalvm-community-jdk-17.0.9_linux-x64_bin.tar.gz -C /opt \
    && mv /opt/graalvm-community-openjdk-17.0.9+9.1/* /opt/graalvm/ \
    && rm -rf /opt/graalvm-community-openjdk-17.0.9+9.1 \
    && rm graalvm-community-jdk-17.0.9_linux-x64_bin.tar.gz \
    && /opt/graalvm/bin/gu install native-image

# OpenJ9
RUN wget -q https://github.com/ibmruntimes/semeru17-binaries/releases/download/jdk-17.0.14%2B7_openj9-0.49.0/ibm-semeru-open-jdk_x64_linux_17.0.14_7_openj9-0.49.0.tar.gz \
    && mkdir -p /opt/openj9 \
    && tar -xzf ibm-semeru-open-jdk_x64_linux_17.0.14_7_openj9-0.49.0.tar.gz -C /opt \
    && mv /opt/jdk-17.0.14+7/* /opt/openj9/ \
    && rm -rf /opt/jdk-17.0.14+7 \
    && rm ibm-semeru-open-jdk_x64_linux_17.0.14_7_openj9-0.49.0.tar.gz

# AxiomJDK (пока не доступно в докере из-за ограничений лицензии)
# ВАРИАНТ 1: Если у вас есть .deb пакет локально
# Поместите axiomjdk-jdk17.0.15+10-linux-amd64.deb в корень проекта
# COPY axiomjdk-jdk17.0.15+10-linux-amd64.deb /tmp/
# RUN apt-get update && apt-get install -y /tmp/axiomjdk-jdk17.0.15+10-linux-amd64.deb \
#     && rm /tmp/axiomjdk-jdk17.0.15+10-linux-amd64.deb

# ВАРИАНТ 2: Если у вас есть URL для скачивания
# RUN wget -q URL_К_ВАШЕМУ_ПАКЕТУ/axiomjdk-jdk17.0.15+10-linux-amd64.deb -O /tmp/axiomjdk.deb \
#     && apt-get update && apt-get install -y /tmp/axiomjdk.deb \
#     && rm /tmp/axiomjdk.deb



# # --- HOTSPOT 21 ---
# RUN wget -q https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz \
#     && mkdir -p /opt/hotspot21 \
#     && tar -xzf jdk-21_linux-x64_bin.tar.gz -C /opt/hotspot21 --strip-components=1 \
#     && rm jdk-21_linux-x64_bin.tar.gz
# # Устанавливаем GraalVM 21
# RUN wget -q https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz \
#     && mkdir -p /opt/graalvm21 \
#     && tar -xzf graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz -C /opt \
#     && mv /opt/graalvm-community-openjdk-21.0.2+13.1/* /opt/graalvm21/ \
#     && rm -rf /opt/graalvm-community-openjdk-21.0.2+13.1 \
#     && rm graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz \
#     && /opt/graalvm21/bin/gu install native-image
#
# # Устанавливаем OpenJ9 21
# RUN wget -q https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.7%2B6_openj9-0.51.0/ibm-semeru-open-jdk_x64_linux_21.0.7_6_openj9-0.51.0.tar.gz \
#     && mkdir -p /opt/openj9vm21 \
#     && tar -xzf ibm-semeru-open-jdk_x64_linux_21.0.7_6_openj9-0.51.0.tar.gz -C /opt \
#     && mv /opt/jdk-21.0.7+6/* /opt/openj9vm21/ \
#     && rm -rf /opt/jdk-21.0.7+6 \
#     && rm ibm-semeru-open-jdk_x64_linux_21.0.7_6_openj9-0.51.0.tar.gz

# Gradle
RUN wget -q https://services.gradle.org/distributions/gradle-7.6-bin.zip \
    && unzip gradle-7.6-bin.zip -d /opt \
    && ln -s /opt/gradle-7.6/bin/gradle /usr/bin/gradle \
    && rm gradle-7.6-bin.zip

# Настройка переменных окружения
ENV JAVA_HOTSPOT=/usr/local/openjdk-17
ENV JAVA_OPENJ9=/opt/openj9
ENV JAVA_GRAALVM=/opt/graalvm
ENV JAVA_AXIOM=/usr/lib/jvm/axiomjdk-java17-amd64/bin/java

WORKDIR /app
COPY . /app

RUN mkdir -p /app/src/main/resources/

# jvm_config.json — пути к JVM внутри контейнера
RUN echo '{ \
  "HotSpotJvm": "/usr/local/openjdk-17/bin/java", \
  "OpenJ9Jvm": "/opt/openj9/bin/java", \
  "GraalVmJvm": "/opt/graalvm/bin/java", \
  "AxiomJvm": "/usr/lib/jvm/axiomjdk-java17-amd64/bin/java" \
}' > /app/src/main/resources/jvm_config.json

# настройки для модели
RUN echo '{ \
  "provider": "OPENAI", \
  "baseUrl": "", \
  "apiKey": "", \
  "model": "", \
  "temperature": 0.3, \
  "topP": 0.9, \
  "maxTokens": 16000, \
  "timeoutSeconds": 200 \
}' > /app/src/main/resources/setting.json

# Сборка JAR — запекает setting.json без секретов
RUN chmod +x ./gradlew \
 && ./gradlew clean jar -x test

RUN mkdir -p /app/anomalies \
             /app/data/openjdk_bugs \
             /app/src/test/resources/GeneratedFromBugs \
             /app/src/test/resources/InitialSeedExamples

# Объявляем переменные окружения — должны быть переданы при docker run
ENV LLM_PROVIDER="" \
    LLM_API_KEY="" \
    LLM_BASE_URL="" \
    LLM_MODEL=""

ENTRYPOINT ["java", "-jar", "build/libs/JvmPerfomanceFuzzing-1.0.jar"]
CMD ["-n", "100"]
