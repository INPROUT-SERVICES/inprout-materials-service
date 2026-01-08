# --- Estágio 1: Build (Compilação) ---
# Usa a mesma imagem do Maven que seu monólito usa
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copia os arquivos do projeto para dentro do container
COPY pom.xml .
COPY src ./src

# Compila o projeto e gera o .jar (pula testes para ser mais rápido)
RUN mvn clean package -DskipTests

# --- Estágio 2: Execução ---
# Usa a imagem leve do Java 21 (igual ao monólito)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Pega o .jar gerado no estágio anterior
COPY --from=build /app/target/*.jar app.jar

# Expõe a porta 8081 (Específica deste serviço)
EXPOSE 8081

# Inicia a aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]