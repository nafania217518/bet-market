// Load Generator: bots that call the API gateway like real users. It never creates markets.
apply(plugin = "org.springframework.boot")

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.boot:spring-boot-starter-restclient")
    "implementation"("tools.jackson.module:jackson-module-kotlin")
}
