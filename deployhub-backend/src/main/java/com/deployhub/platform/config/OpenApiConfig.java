package com.deployhub.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI deployHubOpenApi() {
        return new OpenAPI().info(new Info()
                .title("DeployHub Control-Plane API")
                .version("0.6.0-milestone-6")
                .description("""
                        Deployments go through a durable job queue (retries, leases, idempotency keys). In REAL mode
                        a worker fetches the public repository, builds it with Docker and runs it on this machine;
                        in SIMULATED mode nothing is built or run.
                        Authentication uses a server-side session: log in via POST /api/auth/login, and send the
                        XSRF-TOKEN cookie back as an X-XSRF-TOKEN header on every POST/PUT/DELETE."""));
    }
}
