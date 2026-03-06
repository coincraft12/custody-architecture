package lab.custody;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CustodyApplication {

	public static void main(String[] args) {
		SpringApplication.run(CustodyApplication.class, args);
	}

}
