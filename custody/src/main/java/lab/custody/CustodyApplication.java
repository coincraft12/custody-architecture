package lab.custody;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 13-1-2: OpenAPI definition — title, version, description.
 * Swagger UI: /swagger-ui.html (disabled in production profile via springdoc.api-docs.enabled=false)
 */
@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(
        info = @Info(
                title = "Custody API",
                version = "v1",
                description = "Custody service — secure withdrawal and whitelist management",
                license = @License(name = "Proprietary")
        ),
        servers = {
                @Server(url = "/", description = "Default server")
        }
)
public class CustodyApplication {

	public static void main(String[] args) {
		SpringApplication.run(CustodyApplication.class, args);
	}

}
