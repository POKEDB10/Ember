# Ember web server for Render (or any container host). Data lives in rootz.so, encrypted;
# the container disk is only a cache, so the free plan's wiped disk is fine.

# Build stage: compile with the JDK, no build tool or libraries needed.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY src/main ./src/main
RUN find src/main/java -name '*.java' > sources.txt \
    && javac --release 17 -encoding UTF-8 -d classes @sources.txt \
    && cp -R src/main/resources/. classes/

# Run stage: JRE only (~60 MB smaller than the JDK).
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/classes ./classes
# Tuned for Render's free 512 MB instance: small heap, serial GC, C1-only JIT (less CPU and RAM).
ENV JAVA_TOOL_OPTIONS="-Xmx320m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m -XX:TieredStopAtLevel=1"
EXPOSE 10000
# PORT is set by Render. --trust-proxy: only Render's proxy can reach this container. EMBER_ARGS adds options, e.g. "--admin-user yourname".
CMD ["sh", "-c", "exec java -cp classes edu.ember.server.EmberServer --no-tls --rootz --trust-proxy --http-bind 0.0.0.0 --http-port ${PORT:-10000} --storage /app/data --durability relaxed ${EMBER_ARGS:-}"]
