FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY manifest.mf ./
COPY src ./src
COPY resources ./resources
RUN mkdir -p out && javac --release 17 -d out src/app/MarketLedger.java \
    && cp -r resources out/resources \
    && jar cfm MarketLedger-Pro-Cloud-iPhone-V2.jar manifest.mf -C out .

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/MarketLedger-Pro-Cloud-iPhone-V2.jar /app/MarketLedger-Pro-Cloud-iPhone-V2.jar
EXPOSE 10000
CMD ["java","-jar","/app/MarketLedger-Pro-Cloud-iPhone-V2.jar"]
