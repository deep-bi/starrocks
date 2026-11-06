FROM ubuntu:24.04

# Runtime dependencies for the FE and BE binaries from the RC
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless libaio1t64 libnuma1 libatomic1 libssl3t64 \
        ca-certificates curl procps tzdata && \
    ln -s "$(dirname "$(dirname "$(readlink -f /usr/bin/java)")")" /opt/java && \
    mkdir -p /etc/pki/tls/certs && \
    ln -s /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java TZ=UTC AWS_EC2_METADATA_DISABLED=true
