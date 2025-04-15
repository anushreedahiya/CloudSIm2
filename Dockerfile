FROM openjdk:11-jdk-slim

WORKDIR /app

# Copy the Maven project files
COPY pom.xml .
COPY modules/cloudsim/pom.xml modules/cloudsim/
COPY modules/cloudsim-examples/pom.xml modules/cloudsim-examples/

# Copy the source code
COPY modules/cloudsim/src modules/cloudsim/src
COPY modules/cloudsim-examples/src modules/cloudsim-examples/src

# Build the project (skip tests to speed up the build)
RUN apt-get update && \
    apt-get install -y maven && \
    mvn clean install -DskipTests && \
    apt-get remove -y maven && \
    apt-get autoremove -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Define the command to run the AutoScaling example
ENTRYPOINT ["java", "-cp", "modules/cloudsim-examples/target/classes:modules/cloudsim/target/classes", "org.cloudbus.cloudsim.examples.AutoScaling"] 