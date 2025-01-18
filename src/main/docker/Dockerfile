FROM gcr.io/distroless/java21-debian12:nonroot
COPY cindy-1.0-SNAPSHOT.jar /home
RUN [ "java", "-Djava.util.logging.SimpleFormatter.format=[%1$tF %1$tT.%1$tL] [%4$s] %5$s%n", "-XX:ArchiveClassesAtExit=cindy.jsa", "-jar", "/home/cindy-1.0-SNAPSHOT.jar", "--stop" ]
RUN [ "java", "-Djava.util.logging.SimpleFormatter.format=[%1$tF %1$tT.%1$tL] [%4$s] %5$s%n", "-XX:ArchiveClassesAtExit=healthcheck.jsa", "-jar", "/home/cindy-1.0-SNAPSHOT.jar", "--check-health", "--ignore-errors" ]
EXPOSE 8080
ENTRYPOINT [ "java", "-Djava.util.logging.SimpleFormatter.format=[%1$tF %1$tT.%1$tL] [%4$s] %5$s%n", "-XX:SharedArchiveFile=cindy.jsa", "-jar", "/home/cindy-1.0-SNAPSHOT.jar" ]
HEALTHCHECK CMD [ "java", "-Djava.util.logging.SimpleFormatter.format=[%1$tF %1$tT.%1$tL] [%4$s] %5$s%n", "-XX:SharedArchiveFile=healthcheck.jsa", "-jar", "/home/cindy-1.0-SNAPSHOT.jar", "--check-health" ]
