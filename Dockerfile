FROM maven:3.9-eclipse-temurin-21

WORKDIR /app

COPY pom.xml .
COPY src ./src

# Fails the image build if the suite is red, so a green build is proof the tests pass.
RUN mvn -B test

# Default: print the six-day replay under both readings of the overdraft rule.
CMD ["mvn", "-B", "-q", "compile", "exec:java"]
