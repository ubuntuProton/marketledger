FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY manifest.mf ./
COPY src ./src
COPY resources ./resources
RUN mkdir -p out && javac --release 17 -d out src/app/MarketLedger.java \
    && cp -r resources out/resources \
    && jar cfm MarketLedger-Pro-Cloud-iPhone-V5C.jar manifest.mf -C out .

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends libpostgresql-jdbc-java \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/MarketLedger-Pro-Cloud-iPhone-V5C.jar /app/MarketLedger-Pro-Cloud-iPhone-V5C.jar
EXPOSE 10000
CMD ["java","-cp","/app/MarketLedger-Pro-Cloud-iPhone-V5C.jar:/usr/share/java/postgresql.jar","app.MarketLedger"]
