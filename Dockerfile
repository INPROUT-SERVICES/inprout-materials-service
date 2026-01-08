# Usa uma imagem base com Java 17 (mesma versão do seu pom.xml)
FROM openjdk:17-jdk-slim

# Define o diretório de trabalho dentro do container
WORKDIR /app

# Copia o arquivo .jar gerado pelo Maven para dentro do container
# (Certifique-se de rodar 'mvn clean package' antes de subir o docker)
COPY target/inprout-materials-service-0.0.1-SNAPSHOT.jar app.jar

# Expõe a porta 8081 (definida no seu application.properties)
EXPOSE 8081

        # Comando para iniciar a aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]